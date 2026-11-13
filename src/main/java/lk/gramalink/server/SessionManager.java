package lk.gramalink.server;

import lk.gramalink.shared.Message;
import lk.gramalink.shared.FileChunk;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Tracks authenticated TCP connections, not authentication credentials.
 * Multiple connections for one user are retained independently.
 */
public final class SessionManager {
    private final ConcurrentHashMap<Socket, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Socket, ConnectionWriter> writers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Socket, HeartbeatState> heartbeats = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public SessionManager() {
        this(System::nanoTime);
    }

    /** Supplies monotonic nanosecond ticks, allowing timeout tests without sleeping. */
    public SessionManager(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "Monotonic time source is required.");
    }

    private static final class HeartbeatState {
        private final Session session;
        private long lastHeartbeatNanos;
        private boolean expiring;

        private HeartbeatState(Session session, long lastHeartbeatNanos) {
            this.session = session;
            this.lastHeartbeatNanos = lastHeartbeatNanos;
        }
    }

    /**
     * Server-side identity supplied only after AuthService verifies authentication
     * and role membership. This value checks shape only; constructing it does not
     * authenticate anyone. Never construct it directly from a client message,
     * claimed username/role, LOGIN_OK, or an unverified token.
     */
    public record VerifiedIdentity(String userId, String role) {
        public VerifiedIdentity {
            requireText(userId, "User ID");
            requireText(role, "Role");
        }
    }

    /** A local session entry; it is not a bearer token or a login response. */
    public record Session(VerifiedIdentity identity, Socket connection) {
        public Session {
            Objects.requireNonNull(identity, "Verified identity is required.");
            Objects.requireNonNull(connection, "Connection is required.");
        }
    }

    /**
     * Integration point for Shadhir's AuthService: once the backend has
     * authenticated credentials and validated the role, pass its canonical user
     * ID/role here with the handler's live socket. BackendDispatcher calls this
     * after credential verification; network-only fixtures do not implement LOGIN.
     *
     * The same identity/socket registration is idempotent. Changing identities on
     * an already registered connection is rejected. Close a disconnected socket
     * before removing its session, so concurrent registration cannot revive it.
     */
    public Session registerVerifiedSession(VerifiedIdentity identity, Socket connection) {
        Objects.requireNonNull(identity, "Verified identity is required.");
        Objects.requireNonNull(connection, "Connection is required.");
        final ConnectionWriter writer;
        try {
            writer = writerFor(connection);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot register an inactive connection.", exception);
        }
        synchronized (writer) {
            try {
                return sessions.compute(connection, (socket, existing) -> {
                    if (!isActive(socket) || writer.isClosing()) {
                        throw new IllegalStateException("Cannot register an inactive connection.");
                    }
                    if (existing != null) {
                        if (!existing.identity().equals(identity)) {
                            throw new IllegalStateException("Connection already belongs to a different identity.");
                        }
                        return existing;
                    }
                    Session session = new Session(identity, socket);
                    heartbeats.put(socket, new HeartbeatState(session, nanoTime.getAsLong()));
                    return session;
                });
            } finally {
                // A close between writer lookup and registration must not retain a writer.
                if (!isActive(connection)) {
                    writers.remove(connection, writer);
                }
            }
        }
    }

    /** Immutable snapshot; concurrent connect/disconnect events may change later results. */
    public List<Session> findOnlineSessions(String userId) {
        requireText(userId, "User ID");
        removeInactiveSessions();
        return sessions.values().stream()
                .filter(session -> session.identity().userId().equals(userId))
                .toList();
    }

    public boolean isOnline(String userId) {
        return !findOnlineSessions(userId).isEmpty();
    }

    /** Resolves the server-verified identity for this exact connection, never from client claims. */
    public Optional<Session> findSession(Socket connection) {
        Objects.requireNonNull(connection, "Connection is required.");
        Session session = sessions.get(connection);
        ConnectionWriter writer = writers.get(connection);
        return session != null && isActive(connection) && writer != null && !writer.isClosing()
                ? Optional.of(session) : Optional.empty();
    }

    /** Removes only this connection; other connections for the user remain online. */
    public void removeSession(Socket connection) {
        Objects.requireNonNull(connection, "Connection is required.");
        ConnectionWriter writer = writers.get(connection);
        if (writer == null) {
            removeHeartbeat(connection, sessions.remove(connection));
            return;
        }
        synchronized (writer) {
            removeHeartbeat(connection, sessions.remove(connection));
            // Retain the sole writer while the socket stays open, even after logout.
            if (!isActive(connection)) {
                writers.remove(connection, writer);
            }
        }
    }

    /** Counts authenticated connections, rather than distinct users. */
    public int getAuthenticatedSessionCount() {
        removeInactiveSessions();
        return sessions.size();
    }

    /** Refreshes only a currently authenticated connection using server time. */
    public boolean recordHeartbeat(Socket connection) {
        Objects.requireNonNull(connection, "Connection is required.");
        HeartbeatState state = heartbeats.get(connection);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            if (state.expiring || sessions.get(connection) != state.session || !isActive(connection)) {
                return false;
            }
            state.lastHeartbeatNanos = nanoTime.getAsLong();
            return true;
        }
    }

    /** Returns a monotonic tick value, not a wall-clock date or client-provided time. */
    public OptionalLong getLastHeartbeatNanos(Socket connection) {
        Objects.requireNonNull(connection, "Connection is required.");
        HeartbeatState state = heartbeats.get(connection);
        if (state == null) {
            return OptionalLong.empty();
        }
        synchronized (state) {
            if (state.expiring || sessions.get(connection) != state.session || !isActive(connection)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(state.lastHeartbeatNanos);
        }
    }

    /**
     * Claims expired sessions atomically against heartbeat refresh and registration.
     * Socket closure does not wait for a writer lock, so a stalled write cannot
     * prevent the monitor from disconnecting its client. Reconnection needs a new
     * socket and a new backend-verified session.
     */
    public int expireSessions(Duration timeout) {
        Objects.requireNonNull(timeout, "Heartbeat timeout is required.");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Heartbeat timeout must be positive.");
        }
        final long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Heartbeat timeout is too large.", exception);
        }
        long now = nanoTime.getAsLong();
        int expired = 0;
        for (HeartbeatState state : heartbeats.values()) {
            Socket socket = state.session.connection();
            ConnectionWriter writer = writers.get(socket);
            boolean[] claimed = {false};
            synchronized (state) {
                if (state.expiring || now - state.lastHeartbeatNanos < timeoutNanos) {
                    continue;
                }
                sessions.computeIfPresent(socket, (connection, current) -> {
                    if (current != state.session || heartbeats.get(socket) != state) {
                        return current;
                    }
                    state.expiring = true;
                    if (writer != null) {
                        writer.markClosing();
                    }
                    claimed[0] = true;
                    return null;
                });
            }
            if (!claimed[0]) {
                continue;
            }
            try {
                socket.close();
            } catch (IOException exception) {
                System.err.println("Unable to close timed-out client: " + socket.getRemoteSocketAddress());
            } finally {
                heartbeats.remove(socket, state);
                if (writer != null && !isActive(socket)) {
                    writers.remove(socket, writer);
                }
            }
            System.out.println("Heartbeat timeout: client " + socket.getRemoteSocketAddress() + " marked offline.");
            expired++;
        }
        return expired;
    }

    private void removeHeartbeat(Socket connection, Session session) {
        if (session != null) {
            heartbeats.computeIfPresent(connection,
                    (socket, state) -> state.session == session ? null : state);
        }
    }

    private void removeInactiveSessions() {
        sessions.forEach((socket, session) -> {
            if (!isActive(socket)) {
                removeSession(socket);
            }
        });
    }

    /** Shared by ClientHandler and authenticated routing; never creates a second writer. */
    ConnectionWriter writerFor(Socket socket) throws IOException {
        Objects.requireNonNull(socket, "Connection is required.");
        ConnectionWriter writer = writers.compute(socket, (connection, existing) -> {
            if (existing != null && existing.isClosing()) {
                return existing;
            }
            if (!isActive(connection)) {
                return null;
            }
            return existing == null ? new ConnectionWriter(connection) : existing;
        });
        if (writer == null || writer.isClosing()) {
            throw new IOException("Connection is inactive.");
        }
        return writer;
    }

    /** Rechecks authentication under the same lock used by replies and lifecycle changes. */
    boolean sendToSession(Session session, Message event) throws IOException {
        return writeToSession(session, writer -> writer.send(event));
    }

    /** Trusted transport API; the caller must hold a backend-authorized transfer grant. */
    public boolean sendChunkToSession(Session session, FileChunk chunk) throws IOException {
        Objects.requireNonNull(chunk, "Chunk is required.");
        return writeToSession(session, writer -> writer.sendChunk(chunk));
    }

    @FunctionalInterface
    private interface WriteOperation {
        void write(ConnectionWriter writer) throws IOException;
    }

    private boolean writeToSession(Session session, WriteOperation operation) throws IOException {
        Objects.requireNonNull(session, "Session is required.");
        Socket socket = session.connection();
        ConnectionWriter writer = writers.get(socket);
        if (writer == null) {
            return false;
        }
        synchronized (writer) {
            if (sessions.get(socket) != session) {
                return false;
            }
            try {
                if (!isActive(socket)) {
                    throw new IOException("Recipient connection is inactive.");
                }
                operation.write(writer);
                return true;
            } catch (IOException exception) {
                // Close without waiting on another write lock, unblocking any socket read.
                try {
                    socket.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                } finally {
                    sessions.remove(socket, session);
                    removeHeartbeat(socket, session);
                    writers.remove(socket, writer);
                }
                throw exception;
            }
        }
    }

    private static boolean isActive(Socket socket) {
        return socket.isConnected() && !socket.isClosed()
                && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " is required.");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
    }
}
