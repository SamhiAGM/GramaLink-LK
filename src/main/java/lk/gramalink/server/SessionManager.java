package lk.gramalink.server;

import lk.gramalink.shared.Message;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks authenticated TCP connections, not authentication credentials.
 * Multiple connections for one user are retained independently.
 */
public final class SessionManager {
    private final ConcurrentHashMap<Socket, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Socket, ConnectionWriter> writers = new ConcurrentHashMap<>();

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
     * Integration point for Shadhir's future AuthService: once the backend has
     * authenticated credentials and validated the role, pass its canonical user
     * ID/role here with the handler's live socket. No network message calls this
     * method in Phase 1; LOGIN remains NOT_IMPLEMENTED.
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
                    if (!isActive(socket)) {
                        throw new IllegalStateException("Cannot register an inactive connection.");
                    }
                    if (existing != null) {
                        if (!existing.identity().equals(identity)) {
                            throw new IllegalStateException("Connection already belongs to a different identity.");
                        }
                        return existing;
                    }
                    return new Session(identity, socket);
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

    /** Removes only this connection; other connections for the user remain online. */
    public void removeSession(Socket connection) {
        Objects.requireNonNull(connection, "Connection is required.");
        ConnectionWriter writer = writers.get(connection);
        if (writer == null) {
            sessions.remove(connection);
            return;
        }
        synchronized (writer) {
            sessions.remove(connection);
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
            if (!isActive(connection)) {
                return null;
            }
            return existing == null ? new ConnectionWriter(connection) : existing;
        });
        if (writer == null) {
            throw new IOException("Connection is inactive.");
        }
        return writer;
    }

    /** Rechecks authentication under the same lock used by replies and lifecycle changes. */
    boolean sendToSession(Session session, Message event) throws IOException {
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
                writer.send(event);
                return true;
            } catch (IOException exception) {
                // Close without waiting on another write lock, unblocking any socket read.
                try {
                    socket.close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                } finally {
                    sessions.remove(socket, session);
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
