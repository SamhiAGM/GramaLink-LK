package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatSessionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(35);
    // Represents a backend-verified identity in tests only; production performs no fake login.
    private static final SessionManager.VerifiedIdentity USER =
            new SessionManager.VerifiedIdentity("heartbeat-user", "CITIZEN");

    @Test
    void verifiedRegistrationInitializesTheHeartbeatFromServerTime() throws IOException {
        var now = new AtomicLong(1234);
        var sessions = new SessionManager(now::get);
        try (SocketPair connection = new SocketPair()) {
            sessions.registerVerifiedSession(USER, connection.server);

            assertEquals(1234, sessions.getLastHeartbeatNanos(connection.server).orElseThrow());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void authenticatedHeartbeatUpdatesOnlyItsServerTimestamp() throws IOException {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair()) {
            sessions.registerVerifiedSession(USER, first.server);
            sessions.registerVerifiedSession(USER, second.server);
            now.set(Duration.ofSeconds(10).toNanos());

            assertTrue(sessions.recordHeartbeat(first.server));
            assertEquals(now.get(), sessions.getLastHeartbeatNanos(first.server).orElseThrow());
            assertEquals(0, sessions.getLastHeartbeatNanos(second.server).orElseThrow());
            assertEquals(2, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void unauthenticatedHeartbeatCannotCreateSessionOrTimestamp() throws IOException {
        var now = new AtomicLong(Duration.ofSeconds(10).toNanos());
        var sessions = new SessionManager(now::get);
        try (SocketPair connection = new SocketPair()) {
            assertFalse(sessions.recordHeartbeat(connection.server));
            assertTrue(sessions.getLastHeartbeatNanos(connection.server).isEmpty());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertFalse(sessions.isOnline(USER.userId()));
        }
    }

    @Test
    void repeatedRegistrationDoesNotBypassTheHeartbeatTimeout() throws IOException {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair connection = new SocketPair()) {
            var original = sessions.registerVerifiedSession(USER, connection.server);
            now.set(Duration.ofSeconds(30).toNanos());

            assertSame(original, sessions.registerVerifiedSession(USER, connection.server));
            assertEquals(0, sessions.getLastHeartbeatNanos(connection.server).orElseThrow());
            now.set(TIMEOUT.toNanos());
            assertEquals(1, sessions.expireSessions(TIMEOUT));
        }
    }

    @Test
    void exactTimeoutRemovesSessionClosesSocketAndPreventsEventDelivery() throws Exception {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair connection = new SocketPair()) {
            sessions.registerVerifiedSession(USER, connection.server);
            now.set(TIMEOUT.toNanos() - 1);
            assertEquals(0, sessions.expireSessions(TIMEOUT));
            assertFalse(connection.server.isClosed());

            now.incrementAndGet();
            assertEquals(1, sessions.expireSessions(TIMEOUT));
            assertTrue(connection.server.isClosed());
            assertEquals(-1, connection.client.getInputStream().read());
            assertFalse(sessions.isOnline(USER.userId()));
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.getLastHeartbeatNanos(connection.server).isEmpty());
            assertFalse(sessions.recordHeartbeat(connection.server));
            assertEquals(0, sessions.expireSessions(TIMEOUT));

            var event = new Message(MessageType.NEW_REQUEST,
                    JsonNodeFactory.instance.objectNode().put("requestId", "private-request"));
            assertTrue(new EventRouter(sessions).sendToUser(USER.userId(), event).offline());
        }
    }

    @Test
    void expiringOneConnectionPreservesAnotherHealthyConnectionOfTheSameUser() throws IOException {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair expired = new SocketPair(); SocketPair healthy = new SocketPair()) {
            sessions.registerVerifiedSession(USER, expired.server);
            var healthySession = sessions.registerVerifiedSession(USER, healthy.server);
            now.set(Duration.ofSeconds(20).toNanos());
            assertTrue(sessions.recordHeartbeat(healthy.server));
            now.set(TIMEOUT.toNanos());

            assertEquals(1, sessions.expireSessions(TIMEOUT));
            assertTrue(expired.server.isClosed());
            assertFalse(healthy.server.isClosed());
            assertTrue(sessions.isOnline(USER.userId()));
            assertEquals(List.of(healthySession), sessions.findOnlineSessions(USER.userId()));
            assertEquals(1, sessions.getAuthenticatedSessionCount());

            now.set(Duration.ofSeconds(55).toNanos());
            assertEquals(1, sessions.expireSessions(TIMEOUT));
            assertFalse(sessions.isOnline(USER.userId()));
        }
    }

    @Test
    void aNewVerifiedSocketCanComeOnlineAfterTheOldConnectionExpires() throws IOException {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair oldConnection = new SocketPair(); SocketPair newConnection = new SocketPair()) {
            sessions.registerVerifiedSession(USER, oldConnection.server);
            now.set(TIMEOUT.toNanos());
            assertEquals(1, sessions.expireSessions(TIMEOUT));

            sessions.registerVerifiedSession(USER, newConnection.server);
            assertFalse(sessions.recordHeartbeat(oldConnection.server));
            assertTrue(sessions.recordHeartbeat(newConnection.server));
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.isOnline(USER.userId()));
            assertEquals(0, sessions.expireSessions(TIMEOUT));
        }
    }

    @Test
    void disconnectCleanupRemovesHeartbeatStateAndCannotBeReversedByAHeartbeat() throws IOException {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        try (SocketPair connection = new SocketPair()) {
            sessions.registerVerifiedSession(USER, connection.server);
            connection.server.close();
            sessions.removeSession(connection.server);
            now.set(Duration.ofMinutes(1).toNanos());

            assertFalse(sessions.recordHeartbeat(connection.server));
            assertTrue(sessions.getLastHeartbeatNanos(connection.server).isEmpty());
            assertEquals(0, sessions.expireSessions(TIMEOUT));
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void timeoutCanCloseAConnectionWhileItsEventWriterIsBlocked() throws Exception {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        var workers = Executors.newFixedThreadPool(2);
        try (var connection = new StalledSocket()) {
            sessions.registerVerifiedSession(USER, connection);
            var event = new Message(MessageType.NOTIFICATION,
                    JsonNodeFactory.instance.objectNode().put("text", "Test notification"));
            Future<EventRouter.DeliveryResult> delivery = workers.submit(
                    () -> new EventRouter(sessions).sendToUser(USER.userId(), event));
            assertTrue(connection.writeStarted.await(5, TimeUnit.SECONDS));
            now.set(TIMEOUT.toNanos());

            Future<Integer> expiry = workers.submit(() -> sessions.expireSessions(TIMEOUT));
            assertEquals(1, expiry.get(5, TimeUnit.SECONDS));
            assertTrue(connection.isClosed());
            assertEquals(1, delivery.get(5, TimeUnit.SECONDS).failedConnections());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void zeroAndNegativeTimeoutsAreRejected() {
        var sessions = new SessionManager(() -> 0);
        assertThrows(IllegalArgumentException.class, () -> sessions.expireSessions(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> sessions.expireSessions(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> sessions.expireSessions(null));
    }

    @Test
    void concurrentHeartbeatsAndDisconnectsCannotLeaveSessionsOnline() throws Exception {
        var now = new AtomicLong(0);
        var sessions = new SessionManager(now::get);
        var connections = new ArrayList<SocketPair>();
        var workers = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 24; index++) {
                var connection = new SocketPair();
                connections.add(connection);
                sessions.registerVerifiedSession(USER, connection.server);
            }
            now.set(Duration.ofSeconds(10).toNanos());
            var start = new CountDownLatch(1);
            var operations = new ArrayList<Future<?>>();
            for (SocketPair connection : connections) {
                operations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int heartbeat = 0; heartbeat < 50; heartbeat++) {
                        sessions.recordHeartbeat(connection.server);
                    }
                    return null;
                }));
                operations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    connection.server.close();
                    sessions.removeSession(connection.server);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> operation : operations) {
                operation.get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertFalse(sessions.isOnline(USER.userId()));
            for (SocketPair connection : connections) {
                assertFalse(sessions.recordHeartbeat(connection.server));
                assertTrue(sessions.getLastHeartbeatNanos(connection.server).isEmpty());
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            for (SocketPair connection : connections) {
                connection.close();
            }
        }
    }

    @Test
    void concurrentRefreshAndExpiryChooseOneConsistentOutcome() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                var now = new AtomicLong(0);
                var sessions = new SessionManager(now::get);
                try (SocketPair connection = new SocketPair()) {
                    sessions.registerVerifiedSession(USER, connection.server);
                    now.set(TIMEOUT.toNanos());
                    var start = new CountDownLatch(1);
                    Future<Boolean> heartbeat = workers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return sessions.recordHeartbeat(connection.server);
                    });
                    Future<Integer> expired = workers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return sessions.expireSessions(TIMEOUT);
                    });
                    start.countDown();
                    int expiredCount = expired.get(5, TimeUnit.SECONDS);
                    boolean refreshed = heartbeat.get(5, TimeUnit.SECONDS);

                    if (expiredCount == 1) {
                        assertFalse(refreshed);
                        assertTrue(connection.server.isClosed());
                        assertEquals(0, sessions.getAuthenticatedSessionCount());
                    } else {
                        assertEquals(0, expiredCount);
                        assertTrue(refreshed);
                        assertFalse(connection.server.isClosed());
                        assertEquals(now.get(), sessions.getLastHeartbeatNanos(connection.server).orElseThrow());
                        assertEquals(1, sessions.getAuthenticatedSessionCount());
                    }
                }
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Test-only blocked network write that is released by closing the socket. */
    private static final class StalledSocket extends Socket {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch closedSignal = new CountDownLatch(1);

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    writeStarted.countDown();
                    try {
                        if (!closedSignal.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("Test write was not interrupted by timeout closure.");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException(exception);
                    }
                    throw new SocketException("Test socket was closed during a write.");
                }
            };
        }

        @Override
        public void close() {
            closed.set(true);
            closedSignal.countDown();
        }
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;

        private SocketPair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                server = listener.accept();
            }
            client.setSoTimeout(5000);
        }

        @Override
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                client.close();
            }
        }
    }
}
