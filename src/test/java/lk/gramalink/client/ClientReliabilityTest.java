package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import lk.gramalink.server.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ClientReliabilityTest {
    static ClientReliability.Policy fast() {
        return new ClientReliability.Policy(Duration.ofMillis(300), Duration.ofMillis(30), Duration.ofMillis(250),
                Duration.ofSeconds(3), Duration.ofMillis(20), Duration.ofMillis(60), 3);
    }
    static void ack(Socket peer, Message request) throws IOException {
        JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), new Message(MessageType.ACK, request.correlationId(), null,
                JsonNodeFactory.instance.objectNode().put("acknowledgedType", "HEARTBEAT")));
    }
    static Message read(Socket peer) throws IOException { return JsonUtil.readMessage(new DataInputStream(peer.getInputStream())); }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) { assertTrue(System.nanoTime() < end, "Condition did not arrive"); Thread.sleep(5); }
    }
    static final class Fixture implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final AtomicReference<ClientReliability> binding = new AtomicReference<>();
        final BlockingQueue<ClientReliability.State> states = new LinkedBlockingQueue<>();
        final AtomicInteger revocations = new AtomicInteger();
        final AtomicBoolean closeOnNextConnection = new AtomicBoolean();
        final NetworkClient client = new NetworkClient("127.0.0.1", server.getLocalPort(), new NetworkClient.Listener() {
            public void onStatus(NetworkClient.Status status) {
                if (status == NetworkClient.Status.CONNECTED) binding.get().connected(); else binding.get().transportDisconnected();
            }
            public void onDisconnectRequested() { binding.get().close(); }
        }, Runnable::run);
        final ClientReliability recovery = new ClientReliability(client, fast(), state -> {
            states.add(state);
            if (state == ClientReliability.State.AUTHENTICATION_REQUIRED && closeOnNextConnection.get()) binding.get().close();
        }, revocations::incrementAndGet);
        Fixture() throws IOException { server.setSoTimeout(3000); binding.set(recovery); }
        Socket connect() throws IOException { client.connect(); Socket peer = server.accept(); peer.setSoTimeout(1000); return peer; }
        public void close() throws IOException { recovery.close(); client.close(); server.close(); }
    }
    @Test void policyBackoffIsExponentialCappedAndHeartbeatFitsServerTimeout() {
        assertEquals(20, fast().backoff(1).toMillis()); assertEquals(40, fast().backoff(2).toMillis());
        assertEquals(60, fast().backoff(3).toMillis()); assertEquals(60, fast().backoff(99).toMillis());
        assertEquals(Duration.ofSeconds(35), ClientReliability.Policy.defaults().serverHeartbeatTimeout());
        assertThrows(IllegalArgumentException.class, () -> new ClientReliability.Policy(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(35), Duration.ofMillis(20), Duration.ofMillis(60), 3));
    }
    @Test void noHeartbeatBeforeAuthenticationThenSchedulesCorrelatedHeartbeat() throws Exception {
        try (Fixture f = new Fixture(); Socket peer = f.connect()) {
            peer.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> peer.getInputStream().read());
            assertEquals(ClientReliability.State.AUTHENTICATION_REQUIRED, f.recovery.state());
            peer.setSoTimeout(1000); f.recovery.authenticated();
            Message heartbeat = read(peer); assertEquals(MessageType.HEARTBEAT, heartbeat.type()); assertNotNull(heartbeat.correlationId());
            ack(peer, heartbeat); assertEquals(ClientReliability.State.ONLINE, f.recovery.state());
            Message next = read(peer); ack(peer, next); assertEquals(MessageType.HEARTBEAT, next.type());
        }
    }
    @Test void heartbeatErrorRevokesAndReconnectDoesNotAuthenticateOrReplay() throws Exception {
        try (Fixture f = new Fixture(); Socket peer = f.connect()) {
            f.recovery.authenticated(); Message heartbeat = read(peer);
            JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), new Message(MessageType.ERROR, heartbeat.correlationId(), null,
                    JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED")));
            try (Socket replacement = f.server.accept()) {
                await(() -> f.recovery.state() == ClientReliability.State.AUTHENTICATION_REQUIRED);
                assertTrue(f.revocations.get() > 0); replacement.setSoTimeout(150);
                assertThrows(SocketTimeoutException.class, () -> replacement.getInputStream().read(), "No password, login, heartbeat or command may be replayed");
            }
        }
    }
    @Test void missingHeartbeatAckClosesStaleSocketAndRecoversLocked() throws Exception {
        try (Fixture f = new Fixture(); Socket peer = f.connect()) {
            f.recovery.authenticated(); assertEquals(MessageType.HEARTBEAT, read(peer).type());
            try (Socket replacement = f.server.accept()) {
                await(() -> f.recovery.state() == ClientReliability.State.AUTHENTICATION_REQUIRED);
                assertTrue(f.revocations.get() > 0); assertEquals(-1, peer.getInputStream().read());
            }
        }
    }
    @Test void refusedReconnectStopsAtConfiguredAttemptLimit() throws Exception {
        int port; try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        AtomicReference<ClientReliability> binding = new AtomicReference<>(); BlockingQueue<ClientReliability.State> states = new LinkedBlockingQueue<>();
        try (NetworkClient client = new NetworkClient("127.0.0.1", port);
             ClientReliability recovery = new ClientReliability(client, fast(), states::add, () -> { })) {
            binding.set(recovery); assertThrows(IOException.class, client::connect); recovery.connectionFailed();
            await(() -> recovery.state() == ClientReliability.State.OFFLINE);
            assertEquals(3, states.stream().filter(state -> state == ClientReliability.State.RECONNECTING).count());
        }
    }
    @Test void intentionalDisconnectDoesNotStartRecovery() throws Exception {
        try (Fixture f = new Fixture(); Socket peer = f.connect()) {
            f.client.disconnect(); assertEquals(ClientReliability.State.OFFLINE, f.recovery.state());
            f.server.setSoTimeout(150); assertThrows(SocketTimeoutException.class, f.server::accept); assertEquals(-1, peer.getInputStream().read());
        }
    }
    @Test void reconnectCompletingAfterCloseCannotLeakASocket() throws Exception {
        try (Fixture f = new Fixture(); Socket peer = f.connect()) {
            f.closeOnNextConnection.set(true); peer.close();
            try (Socket late = f.server.accept()) {
                late.setSoTimeout(1500); assertEquals(-1, late.getInputStream().read());
                assertEquals(ClientReliability.State.OFFLINE, f.recovery.state()); assertFalse(f.client.isConnected());
            }
        }
    }
    @Test void loginServiceRecoveryDropsRoleAndDoesNotStoreOrResendCredentials() throws Exception {
        CitizenProtocol adapter = new CitizenProtocol() {
            public Optional<CitizenData.Identity> citizenAuthorization(Message reply) { return Optional.of(new CitizenData.Identity("fixture-citizen", "Fixture Citizen")); }
        };
        try (ServerSocket server = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run, adapter, GnProtocol.pendingBackend(), DsProtocol.pendingBackend(), AdminProtocol.pendingBackend(), state -> { }, fast())) {
            server.setSoTimeout(3000);
            var login = service.login(new LoginService.Input("127.0.0.1", server.getLocalPort(), "fixture-only", "test-only"));
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(1500); Message request = read(peer);
                JsonUtil.writeMessage(new DataOutputStream(peer.getOutputStream()), new Message(MessageType.LOGIN_OK, request.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                login.get(2, TimeUnit.SECONDS); assertTrue(service.citizenGateway().isPresent());
                ack(peer, read(peer)); peer.close();
                try (Socket replacement = server.accept()) {
                    await(() -> service.recoveryState() == ClientReliability.State.AUTHENTICATION_REQUIRED);
                    assertTrue(service.citizenGateway().isEmpty()); assertTrue(service.fileProgress().isEmpty());
                    replacement.setSoTimeout(150); assertThrows(SocketTimeoutException.class, () -> replacement.getInputStream().read());
                }
            }
        }
    }
    @Test void actualSamhiServerSupportsAuthenticatedScheduledHeartbeatUsingOnlyATestSessionFixture() throws Exception {
        int port; try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        SessionManager sessions = new SessionManager(); AtomicReference<ClientReliability> binding = new AtomicReference<>(); BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        try (NetworkServer server = new NetworkServer(port, sessions);
             ExecutorService serverThread = Executors.newSingleThreadExecutor();
             NetworkClient client = new NetworkClient("127.0.0.1", port, new NetworkClient.Listener() {
                 public void onStatus(NetworkClient.Status value) { if (value == NetworkClient.Status.CONNECTED) binding.get().connected(); else binding.get().transportDisconnected(); }
                 public void onMessage(Message message) { messages.add(message); }
             }, Runnable::run);
             ClientReliability recovery = new ClientReliability(client, fast(), state -> { }, () -> { })) {
            binding.set(recovery); Future<?> serving = serverThread.submit(() -> { server.start(); return null; });
            try {
                client.connect();
                // Existing server test-fixture technique: no production LOGIN/authentication bypass is installed.
                var field = NetworkServer.class.getDeclaredField("connections"); field.setAccessible(true);
                @SuppressWarnings("unchecked") Set<Socket> connections = (Set<Socket>)field.get(server);
                await(() -> !connections.isEmpty());
                sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity("fixture-citizen", "CITIZEN"), connections.iterator().next());
                recovery.authenticated(); Message ack = messages.poll(3, TimeUnit.SECONDS);
                assertNotNull(ack); assertEquals(MessageType.ACK, ack.type()); assertEquals("HEARTBEAT", ack.payload().path("acknowledgedType").asText());
                assertEquals(ClientReliability.State.ONLINE, recovery.state());
            } finally { recovery.close(); client.close(); server.close(); serving.get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void actualSamhiServerUnauthenticatedContractsRemainUnavailable() throws Exception {
        int port; try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        try (NetworkServer server = new NetworkServer(port); ExecutorService worker = Executors.newSingleThreadExecutor(); NetworkClient client = new NetworkClient("127.0.0.1", port)) {
            Future<?> serving = worker.submit(() -> { server.start(); return null; });
            try {
                client.connect();
                for (MessageType type : List.of(MessageType.LOGIN, MessageType.HEARTBEAT, MessageType.FILE_META)) {
                    Message reply = client.request(new Message(type, JsonNodeFactory.instance.objectNode()), Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);
                    assertEquals(MessageType.ERROR, reply.type()); assertEquals("NOT_IMPLEMENTED", reply.payload().path("code").asText());
                }
            } finally { client.close(); server.close(); serving.get(3, TimeUnit.SECONDS); }
        }
    }
}
