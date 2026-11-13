package lk.gramalink.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {
    // These identities stand in for successful backend verification in unit tests only.
    private static final SessionManager.VerifiedIdentity USER =
            new SessionManager.VerifiedIdentity("user-17", "CITIZEN");

    @Test
    void newManagerHasNoAuthenticatedSessions() {
        SessionManager manager = new SessionManager();
        assertEquals(0, manager.getAuthenticatedSessionCount());
        assertFalse(manager.isOnline(USER.userId()));
        assertTrue(manager.findOnlineSessions(USER.userId()).isEmpty());
    }

    @Test
    void verifiedRegistrationPreservesIdentityRoleAndConnection() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair pair = new SocketPair()) {
            SessionManager.Session session = manager.registerVerifiedSession(USER, pair.server);

            assertEquals(USER, session.identity());
            assertEquals("CITIZEN", session.identity().role());
            assertSame(pair.server, session.connection());
            assertEquals(List.of(session), manager.findOnlineSessions(USER.userId()));
            assertTrue(manager.isOnline(USER.userId()));
            assertEquals(1, manager.getAuthenticatedSessionCount());
            assertFalse(manager.isOnline("another-user"));
        }
    }

    @Test
    void identityRequiresNonblankServerSideIdentityAndRole() {
        assertThrows(NullPointerException.class,
                () -> new SessionManager.VerifiedIdentity(null, "CITIZEN"));
        assertThrows(NullPointerException.class,
                () -> new SessionManager.VerifiedIdentity("user", null));
        for (String blank : List.of("", " ", "\t\n")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SessionManager.VerifiedIdentity(blank, "CITIZEN"));
            assertThrows(IllegalArgumentException.class,
                    () -> new SessionManager.VerifiedIdentity("user", blank));
        }
    }

    @Test
    void registeringTheSameVerifiedIdentityOnOneConnectionIsIdempotent() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair pair = new SocketPair()) {
            SessionManager.Session first = manager.registerVerifiedSession(USER, pair.server);
            SessionManager.Session repeated = manager.registerVerifiedSession(
                    new SessionManager.VerifiedIdentity(USER.userId(), USER.role()), pair.server);

            assertSame(first, repeated);
            assertEquals(1, manager.getAuthenticatedSessionCount());
        }
    }

    @Test
    void differentIdentityCannotSilentlyReplaceAnExistingConnectionSession() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair pair = new SocketPair()) {
            SessionManager.Session original = manager.registerVerifiedSession(USER, pair.server);
            assertThrows(IllegalStateException.class, () -> manager.registerVerifiedSession(
                    new SessionManager.VerifiedIdentity("different-user", "CITIZEN"), pair.server));
            assertThrows(IllegalStateException.class, () -> manager.registerVerifiedSession(
                    new SessionManager.VerifiedIdentity(USER.userId(), "OFFICER"), pair.server));

            assertEquals(List.of(original), manager.findOnlineSessions(USER.userId()));
            assertFalse(manager.isOnline("different-user"));
            assertEquals(1, manager.getAuthenticatedSessionCount());
        }
    }

    @Test
    void duplicateUserConnectionsStayIndependentDuringRemoval() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair()) {
            manager.registerVerifiedSession(USER, first.server);
            SessionManager.Session remaining = manager.registerVerifiedSession(USER, second.server);
            assertEquals(2, manager.findOnlineSessions(USER.userId()).size());
            assertEquals(2, manager.getAuthenticatedSessionCount());

            first.server.close();
            manager.removeSession(first.server);
            manager.removeSession(first.server);
            assertTrue(manager.isOnline(USER.userId()));
            assertEquals(List.of(remaining), manager.findOnlineSessions(USER.userId()));
            assertEquals(1, manager.getAuthenticatedSessionCount());

            second.server.close();
            manager.removeSession(second.server);
            assertFalse(manager.isOnline(USER.userId()));
            assertEquals(0, manager.getAuthenticatedSessionCount());
        }
    }

    @Test
    void onlineSessionResultsAreImmutableSnapshots() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair()) {
            SessionManager.Session original = manager.registerVerifiedSession(USER, first.server);
            List<SessionManager.Session> snapshot = manager.findOnlineSessions(USER.userId());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(original));
            manager.registerVerifiedSession(USER, second.server);

            assertEquals(List.of(original), snapshot);
            assertEquals(2, manager.findOnlineSessions(USER.userId()).size());
        }
    }

    @Test
    void missingIdentityAndDisconnectedSocketsCannotBeRegistered() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair pair = new SocketPair(); Socket unconnected = new Socket()) {
            assertThrows(NullPointerException.class, () -> manager.registerVerifiedSession(null, pair.server));
            assertThrows(NullPointerException.class, () -> manager.registerVerifiedSession(USER, null));
            assertThrows(IllegalStateException.class, () -> manager.registerVerifiedSession(USER, unconnected));
            pair.server.close();
            assertThrows(IllegalStateException.class, () -> manager.registerVerifiedSession(USER, pair.server));
            assertEquals(0, manager.getAuthenticatedSessionCount());
        }
    }

    @Test
    void halfClosedConnectionsCannotBeRegistered() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair inputClosed = new SocketPair(); SocketPair outputClosed = new SocketPair()) {
            inputClosed.server.shutdownInput();
            outputClosed.server.shutdownOutput();
            assertThrows(IllegalStateException.class,
                    () -> manager.registerVerifiedSession(USER, inputClosed.server));
            assertThrows(IllegalStateException.class,
                    () -> manager.registerVerifiedSession(USER, outputClosed.server));
            assertEquals(0, manager.getAuthenticatedSessionCount());
        }
    }

    @Test
    void locallyClosedConnectionsAreExcludedFromOnlineQueries() throws IOException {
        SessionManager manager = new SessionManager();
        try (SocketPair pair = new SocketPair()) {
            manager.registerVerifiedSession(USER, pair.server);
            pair.server.close();

            assertEquals(0, manager.getAuthenticatedSessionCount());
            assertFalse(manager.isOnline(USER.userId()));
            assertTrue(manager.findOnlineSessions(USER.userId()).isEmpty());
        }
    }

    @Test
    void connectionsCanRegisterAndBeRemovedConcurrently() throws Exception {
        SessionManager manager = new SessionManager();
        List<SocketPair> pairs = new ArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 40; i++) {
                pairs.add(new SocketPair());
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> registrations = new ArrayList<>();
            for (SocketPair pair : pairs) {
                registrations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    manager.registerVerifiedSession(USER, pair.server);
                    return null;
                }));
            }
            start.countDown();
            awaitAll(registrations);
            assertEquals(40, manager.getAuthenticatedSessionCount());
            assertEquals(40, manager.findOnlineSessions(USER.userId()).size());

            List<Future<?>> removals = new ArrayList<>();
            for (SocketPair pair : pairs) {
                removals.add(workers.submit(() -> {
                    pair.server.close();
                    manager.removeSession(pair.server);
                    return null;
                }));
            }
            awaitAll(removals);
            assertEquals(0, manager.getAuthenticatedSessionCount());
            assertFalse(manager.isOnline(USER.userId()));
        } finally {
            stopWorkers(workers);
            for (SocketPair pair : pairs) {
                pair.close();
            }
        }
    }

    @Test
    void concurrentRegistrationOnOneConnectionProducesOneSession() throws Exception {
        SessionManager manager = new SessionManager();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try (SocketPair pair = new SocketPair()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<SessionManager.Session>> registrations = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                registrations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return manager.registerVerifiedSession(USER, pair.server);
                }));
            }
            start.countDown();
            SessionManager.Session first = registrations.get(0).get(5, TimeUnit.SECONDS);
            for (Future<SessionManager.Session> result : registrations) {
                assertSame(first, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, manager.getAuthenticatedSessionCount());
        } finally {
            stopWorkers(workers);
        }
    }

    @Test
    void racingConnectionClosureCannotResurrectAnAuthenticatedSession() throws Exception {
        SessionManager manager = new SessionManager();
        List<SocketPair> pairs = new ArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 40; i++) {
                pairs.add(new SocketPair());
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> operations = new ArrayList<>();
            for (SocketPair pair : pairs) {
                operations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        manager.registerVerifiedSession(USER, pair.server);
                    } catch (IllegalStateException alreadyClosed) {
                        // Closure may legitimately win the race before registration.
                    }
                    return null;
                }));
                operations.add(workers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    pair.server.close();
                    manager.removeSession(pair.server);
                    return null;
                }));
            }
            start.countDown();
            awaitAll(operations);
            assertEquals(0, manager.getAuthenticatedSessionCount());
            assertTrue(manager.findOnlineSessions(USER.userId()).isEmpty());
        } finally {
            stopWorkers(workers);
            for (SocketPair pair : pairs) {
                pair.close();
            }
        }
    }

    private static void awaitAll(List<? extends Future<?>> results) throws Exception {
        for (Future<?> result : results) {
            result.get(5, TimeUnit.SECONDS);
        }
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;

        private SocketPair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                server = listener.accept();
            }
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
