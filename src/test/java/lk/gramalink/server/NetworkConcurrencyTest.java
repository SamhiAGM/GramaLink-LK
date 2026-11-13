package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** N08 Phase 1: bounded loopback reliability checks, not LAN benchmarks. */
@Timeout(30)
class NetworkConcurrencyTest {
    private static final int CLIENTS = 20;

    @Test
    void twentyClientsStayActiveAndListenerServesAdditionalClients() throws Exception {
        try (Fixture f = new Fixture()) {
            long started = System.nanoTime();
            List<Peer> peers = f.connectTogether(CLIENTS);
            assertEquals(CLIENTS, f.connections().size());
            f.parallel(peers, peer -> {
                for (int i = 0; i < 5; i++) peer.heartbeat(false, "round-" + i);
            });
            // Existing handlers are blocked in reads; accept must still serve another connection.
            Peer extra = f.connect();
            extra.heartbeat(false, "new-client");
            assertEquals(CLIENTS + 1, f.connections().size());
            f.parallel(peers, peer -> peer.heartbeat(false, "still-active"));
            assertEquals(0, f.sessions.getAuthenticatedSessionCount(), "Messages must not authenticate clients.");
            System.out.printf("N08 loopback only: simultaneous=%d, accepted=%d, elapsedMs=%d%n",
                    CLIENTS, CLIENTS + 1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    @Test
    void disconnectAndMalformedJsonAffectOnlyTheirOwnSessions() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Peer> peers = f.connectTogether(CLIENTS);
            f.verifyTestSessions(peers);
            peers.get(0).close();
            await(() -> f.sessions.getAuthenticatedSessionCount() == CLIENTS - 1);
            Peer malformed = peers.get(1);
            malformed.output.writeInt(1);
            malformed.output.writeByte('{');
            malformed.output.flush();
            malformed.assertEof();
            await(() -> f.sessions.getAuthenticatedSessionCount() == CLIENTS - 2);
            await(() -> f.connections().size() == CLIENTS - 2);
            f.parallel(peers.subList(2, CLIENTS), peer -> peer.heartbeat(true, "survivor"));
            assertEquals(CLIENTS - 2, f.sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void heartbeatMonitorExpiresOnlyStaleVerifiedSessions() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Peer> peers = f.connectTogether(CLIENTS);
            f.verifyTestSessions(peers.subList(0, CLIENTS - 1)); // Last peer remains unauthenticated.
            f.now.set(Duration.ofSeconds(34).toNanos());
            f.parallel(peers.subList(1, CLIENTS - 1), peer -> peer.heartbeat(true, "refresh"));
            f.now.set(Duration.ofSeconds(35).toNanos());
            peers.get(0).assertEof(); // Actual monitor checks the injected monotonic clock.
            await(() -> f.sessions.getAuthenticatedSessionCount() == CLIENTS - 2);
            f.parallel(peers.subList(1, CLIENTS - 1), peer -> peer.heartbeat(true, "alive"));
            peers.getLast().heartbeat(false, "unauthenticated-survives");
            assertEquals(CLIENTS - 2, f.sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void concurrentJsonBinaryAndHandlerRepliesDoNotCorruptFramesAcrossTwentyClients() throws Exception {
        try (Fixture f = new Fixture()) {
            List<Peer> peers = f.connectTogether(CLIENTS);
            Map<Peer, SessionManager.Session> verified = f.verifyTestSessions(peers);
            UUID transfer = UUID.randomUUID();
            byte[] bytes = new byte[2048];
            Arrays.fill(bytes, (byte) 0xa5);
            ExecutorService senders = Executors.newFixedThreadPool(CLIENTS * 2);
            CountDownLatch ready = new CountDownLatch(CLIENTS * 2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> pending = new ArrayList<>();
            try {
                for (Peer peer : peers) {
                    pending.add(senders.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int i = 0; i < 10; i++) {
                            assertTrue(f.sessions.sendToSession(verified.get(peer), message(MessageType.NOTIFICATION, "event-" + i)));
                        }
                        return null;
                    }));
                    pending.add(senders.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int i = 0; i < 10; i++) {
                            assertTrue(f.sessions.sendChunkToSession(verified.get(peer), new FileChunk(transfer, i, i == 9, bytes)));
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                f.parallel(peers, peer -> {
                    // Inbound binary is denied without a transfer grant, but must not desynchronize JSON.
                    TransportCodec.writeChunk(peer.output, new FileChunk(transfer, 0, true, bytes));
                    JsonUtil.writeMessage(peer.output, message(MessageType.HEARTBEAT, "heartbeat"));
                    Set<String> jsonIds = new HashSet<>();
                    int chunks = 0;
                    int rejected = 0;
                    for (int i = 0; i < 22; i++) {
                        TransportFrame frame = TransportCodec.readFrame(peer.input);
                        assertNotNull(frame);
                        if (frame instanceof FileChunk chunk) {
                            assertEquals(transfer, chunk.transferId());
                            assertEquals(chunks, chunk.sequence());
                            assertEquals(chunks == 9, chunk.last());
                            assertArrayEquals(bytes, chunk.data());
                            chunks++;
                        } else {
                            Message reply = ((TransportFrame.JsonFrame) frame).message();
                            if (reply.type() == MessageType.ERROR) {
                                assertEquals("UNKNOWN_TRANSFER", reply.payload().path("code").asText());
                                rejected++;
                            } else {
                                assertTrue(jsonIds.add(reply.correlationId()));
                                assertEquals(reply.correlationId().equals("heartbeat") ? MessageType.ACK : MessageType.NOTIFICATION,
                                        reply.type());
                            }
                        }
                    }
                    assertEquals(10, chunks);
                    assertEquals(1, rejected);
                    Set<String> expected = new HashSet<>();
                    expected.add("heartbeat");
                    for (int i = 0; i < 10; i++) expected.add("event-" + i);
                    assertEquals(expected, jsonIds);
                });
                for (Future<?> future : pending) future.get(5, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                // Socket closure also releases any blocked writes if a reader assertion fails.
                for (Peer peer : peers) peer.close();
                senders.shutdownNow();
                assertTrue(senders.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void workersAreReusedAcrossWavesAndShutdownClosesAllActiveConnections() throws Exception {
        try (Fixture f = new Fixture()) {
            for (int wave = 0; wave < 3; wave++) {
                List<Peer> peers = f.connectTogether(CLIENTS);
                f.verifyTestSessions(peers);
                f.parallel(peers, peer -> peer.heartbeat(true, "wave"));
                for (Peer peer : peers) peer.close();
                int completed = (wave + 1) * CLIENTS;
                await(() -> f.workers.getCompletedTaskCount() >= completed);
                await(() -> f.connections().isEmpty());
                assertEquals(0, f.sessions.getAuthenticatedSessionCount());
            }
            assertEquals(32, f.workers.getLargestPoolSize());
            assertEquals(60, f.workers.getCompletedTaskCount(), "More handled connections than workers proves reuse.");
            List<Peer> active = f.connectTogether(CLIENTS);
            f.verifyTestSessions(active);
            f.server.close();
            for (Peer peer : active) peer.assertEof();
            f.assertStopped();
            assertEquals(0, f.sessions.getAuthenticatedSessionCount());
            assertTrue(f.connections().isEmpty());
        }
    }

    @Test
    void productionPortValidationStillRejectsEphemeralAndOutOfRangePorts() {
        for (int port : new int[]{0, -1, 65536}) {
            assertThrows(IllegalArgumentException.class, () -> new NetworkServer(port));
        }
    }

    private static Message message(MessageType type, String id) {
        return new Message(type, id, null, JsonNodeFactory.instance.objectNode());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Condition did not complete within five seconds.");
            // Short bounded polling for asynchronous cleanup, not a fixed readiness sleep.
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    @FunctionalInterface
    private interface PeerAction { void run(Peer peer) throws Exception; }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        Peer(int port) throws IOException {
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
                socket.setSoTimeout(5000);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                socket.close();
                throw e;
            }
        }
        void heartbeat(boolean authenticated, String id) throws IOException {
            JsonUtil.writeMessage(output, message(MessageType.HEARTBEAT, id));
            Message response = JsonUtil.readMessage(input);
            assertNotNull(response);
            assertEquals(id, response.correlationId());
            assertEquals(authenticated ? MessageType.ACK : MessageType.ERROR, response.type());
            assertNull(response.token());
            assertEquals(authenticated ? "HEARTBEAT" : "NOT_IMPLEMENTED",
                    response.payload().path(authenticated ? "acknowledgedType" : "code").asText());
        }
        void assertEof() throws IOException {
            try { assertEquals(-1, input.read()); }
            catch (SocketException reset) { /* TCP reset also confirms peer closure. */ }
        }
        public void close() throws IOException { socket.close(); }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicLong now = new AtomicLong();
        final SessionManager sessions = new SessionManager(now::get);
        final HeartbeatMonitor monitor = new HeartbeatMonitor(sessions, Duration.ofSeconds(35), Duration.ofMillis(10));
        final NetworkServer server;
        final int port;
        final Thread listener;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final List<Peer> owned = new CopyOnWriteArrayList<>();
        final ExecutorService clients = Executors.newFixedThreadPool(CLIENTS);
        final ThreadPoolExecutor workers;
        Fixture() throws Exception {
            ServerSocket bound = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            port = bound.getLocalPort();
            server = new NetworkServer(bound, sessions, monitor);
            workers = (ThreadPoolExecutor) field("workers");
            listener = new Thread(() -> {
                try { server.start(); } catch (Throwable e) { failure.set(e); }
            }, "n08-test-listener");
            listener.start();
        }
        // Read-only observation avoids adding production metrics/session-discovery APIs for tests.
        Object field(String name) throws ReflectiveOperationException {
            var field = NetworkServer.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(server);
        }
        @SuppressWarnings("unchecked")
        Set<Socket> connections() {
            try { return (Set<Socket>) field("connections"); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        Peer connect() throws IOException {
            Peer peer = new Peer(port);
            owned.add(peer);
            return peer;
        }
        List<Peer> connectTogether(int count) throws Exception {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Peer>> tasks = new ArrayList<>();
            try {
                for (int i = 0; i < count; i++) tasks.add(clients.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    Peer peer = connect();
                    peer.heartbeat(false, "connected");
                    return peer;
                }));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                List<Peer> peers = new ArrayList<>();
                for (Future<Peer> task : tasks) peers.add(task.get(5, TimeUnit.SECONDS));
                return peers;
            } finally { start.countDown(); }
        }
        Map<Peer, SessionManager.Session> verifyTestSessions(List<Peer> peers) throws Exception {
            Map<Peer, SessionManager.Session> result = new HashMap<>();
            for (Peer peer : peers) {
                Socket accepted = connections().stream()
                        .filter(socket -> socket.getPort() == peer.socket.getLocalPort()).findFirst().orElseThrow();
                // Test-only backend verification stand-in; never exposed through LOGIN or client claims.
                result.put(peer, sessions.registerVerifiedSession(
                        new SessionManager.VerifiedIdentity("n08-verified-test-user", "CITIZEN"), accepted));
            }
            return result;
        }
        void parallel(List<Peer> peers, PeerAction action) throws Exception {
            CountDownLatch ready = new CountDownLatch(peers.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            try {
                for (Peer peer : peers) tasks.add(clients.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    action.run(peer);
                    return null;
                }));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            } finally { start.countDown(); }
        }
        void assertStopped() throws InterruptedException {
            listener.join(5000);
            assertFalse(listener.isAlive(), "Accept thread must terminate.");
            assertNull(failure.get());
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Server workers must terminate without leaks/deadlock.");
            assertTrue(monitor.isShutdown());
        }
        public void close() throws Exception {
            try { server.close(); }
            finally {
                for (Peer peer : owned) peer.close();
                clients.shutdownNow();
                assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS), "Test workers must terminate.");
                assertStopped();
                assertEquals(0, sessions.getAuthenticatedSessionCount());
            }
        }
    }
}
