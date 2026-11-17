package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DsGatewayTest {
    private static final DsData.Version V1 = new DsData.Version("fixture/rev-A"), V2 = new DsData.Version("fixture/rev-B");
    private static Message message(MessageType type) { return new Message(type, JsonNodeFactory.instance.objectNode()); }
    private static DsData.RequestRow row(DsData.Version version) { return new DsData.RequestRow("fixture-request", "citizen", "GN office", "type", "backend supplied state", null, version); }
    private static DsData.Action action(DsData.ActionKind kind, DsData.Version version) { return new DsData.Action(kind, "fixture-request", version, "fixture reason"); }
    private static <T extends Throwable> T failure(Class<T> type, CompletableFuture<?> future) {
        return assertInstanceOf(type, assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
    }
    /** Test-only adapter: no credential verification or proposed JSON application schema. */
    private static class Adapter implements DsProtocol {
        final AtomicInteger authorizations = new AtomicInteger();
        final AtomicReference<DsData.Update> next = new AtomicReference<>();
        final List<DsData.Action> encoded = new CopyOnWriteArrayList<>();
        boolean versions = true, supported = true, malformed;
        MessageType wrongType;
        public Optional<DsData.Identity> dsAuthorization(Message reply) { authorizations.incrementAndGet(); return Optional.of(new DsData.Identity("fixture-ds", "Fixture DS officer")); }
        public boolean supports(DsData.ActionKind kind) { return supported; }
        public boolean requiresVersion(DsData.ActionKind kind) { return versions && kind != DsData.ActionKind.CHAT; }
        public Message encode(DsData.Action action) {
            encoded.add(action);
            return message(wrongType != null ? wrongType : action.kind() == DsData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE);
        }
        public boolean acceptsEvent(Message message) { return true; }
        public Optional<DsData.Update> decodeEvent(Message message) {
            if (malformed) throw new IllegalArgumentException("Malformed fixture");
            return Optional.ofNullable(next.getAndSet(null));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final NetworkClient client;
        final Adapter adapter = new Adapter();
        final DsGateway gateway;
        final BlockingQueue<DsData.Update> updates = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        Fixture() { this(new NetworkClient()); }
        Fixture(NetworkClient client) {
            this.client = client; gateway = new DsGateway(client, adapter, Runnable::run);
            gateway.setListener(new DsGateway.Listener() {
                public void onUpdate(DsData.Update update) { updates.add(update); }
                public void onError(String error) { errors.add(error); }
            });
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
        }
        void publish(DsData.Update update) throws Exception {
            adapter.next.set(update); gateway.incoming(message(MessageType.REQUEST_UPDATED)); assertSame(update, updates.poll(3, TimeUnit.SECONDS));
        }
        public void close() { gateway.close(); client.close(); }
    }

    @Test void pendingProtocolRejectsRoleClaimsAndGuardsBothDashboardEntries() throws Exception {
        try (NetworkClient client = new NetworkClient(); DsGateway gateway = new DsGateway(client, DsProtocol.pendingBackend(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            for (String role : List.of("DS_OFFICER", "DS", "GN_OFFICER", "CITIZEN")) {
                gateway.acceptLoginReply(new Message(MessageType.LOGIN_OK, "claim", "unverified-token", JsonNodeFactory.instance.objectNode().put("role", role).put("userId", "claimed")));
                assertFalse(gateway.isDsAuthorized()); assertTrue(gateway.identity().isEmpty());
            }
            assertThrows(SecurityException.class, () -> DsDashboardView.open(null, gateway));
            assertThrows(SecurityException.class, () -> new DsDashboardController().attach(gateway, () -> { }));
            failure(SecurityException.class, gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
        }
    }
    @Test void unsolicitedLoginCannotAuthorizeAndDisconnectRevokesState() throws Exception {
        Adapter adapter = new Adapter();
        try (NetworkClient client = new NetworkClient(); DsGateway gateway = new DsGateway(client, adapter, Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.incoming(message(MessageType.LOGIN_OK)); assertFalse(gateway.isDsAuthorized());
            gateway.acceptLoginReply(message(MessageType.ERROR)); assertFalse(gateway.isDsAuthorized());
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); assertTrue(gateway.isDsAuthorized());
            gateway.connectionStatus(NetworkClient.Status.DISCONNECTED); assertFalse(gateway.isDsAuthorized());
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); assertFalse(gateway.isDsAuthorized());
            failure(SecurityException.class, gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
        }
    }
    @Test void versionRulesRejectMissingStaleAndUnscopedActionsBeforeEncoding() throws Exception {
        try (Fixture f = new Fixture()) {
            f.publish(new DsData.Requests(List.of(row(V1))));
            failure(IllegalArgumentException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, null)));
            failure(IllegalArgumentException.class, f.gateway.perform(action(DsData.ActionKind.REJECT, V2)));
            failure(SecurityException.class, f.gateway.perform(new DsData.Action(DsData.ActionKind.APPROVE, "unknown", V1, "")));
            assertTrue(f.adapter.encoded.isEmpty());
            f.adapter.supported = false;
            failure(UnsupportedOperationException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
            assertTrue(f.adapter.encoded.isEmpty());
        }
    }
    @Test void contractCanExplicitlyAllowUnversionedActionsWithoutInventedRevision() throws Exception {
        try (Fixture f = new Fixture()) {
            f.adapter.versions = false; f.publish(new DsData.Requests(List.of(row(null))));
            // Reaches the real client but cannot write because this fixture has no connected socket.
            failure(IOException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, null)));
            assertEquals(1, f.adapter.encoded.size()); assertNull(f.adapter.encoded.getFirst().expectedVersion());
        }
    }
    @Test void currentVersionReachesEncoderAndOnlyExistingTypesAreWritten() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort())) {
            listener.setSoTimeout(3000); client.connect();
            try (Socket peer = listener.accept(); Fixture f = new Fixture(client)) {
                peer.setSoTimeout(3000); var input = new DataInputStream(peer.getInputStream()); var output = new DataOutputStream(peer.getOutputStream());
                f.publish(new DsData.Requests(List.of(row(V1))));
                for (DsData.ActionKind kind : DsData.ActionKind.values()) {
                    var pending = f.gateway.perform(action(kind, V1)); Message sent = JsonUtil.readMessage(input);
                    assertEquals(kind == DsData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE, sent.type());
                    assertNotNull(sent.correlationId()); assertEquals(V1, f.adapter.encoded.getLast().expectedVersion());
                    JsonUtil.writeMessage(output, new Message(MessageType.ACK, sent.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                    assertEquals(MessageType.ACK, pending.get(3, TimeUnit.SECONDS).type());
                }
                assertTrue(f.updates.isEmpty(), "ACK must not generate an approved/rejected update");
                f.adapter.wrongType = MessageType.LOGIN;
                failure(IllegalArgumentException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
            }
        }
    }
    @Test void conflictAndForbiddenBlockRetriesUntilFreshAuthoritativeSnapshot() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort())) {
            listener.setSoTimeout(3000); client.connect();
            try (Socket peer = listener.accept(); Fixture f = new Fixture(client)) {
                peer.setSoTimeout(3000); var input = new DataInputStream(peer.getInputStream()); var output = new DataOutputStream(peer.getOutputStream());
                f.publish(new DsData.Requests(List.of(row(V1))));
                for (String code : List.of("CONFLICT", "FORBIDDEN")) {
                    var pending = f.gateway.perform(action(DsData.ActionKind.APPROVE, V1)); Message sent = JsonUtil.readMessage(input);
                    JsonUtil.writeMessage(output, new Message(MessageType.ERROR, sent.correlationId(), null, JsonNodeFactory.instance.objectNode().put("code", code)));
                    assertEquals(code, pending.get(3, TimeUnit.SECONDS).payload().path("code").asText());
                    int sentCount = f.adapter.encoded.size();
                    assertTrue(failure(IllegalArgumentException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, V1))).getMessage().contains("Reload"));
                    assertEquals(sentCount, f.adapter.encoded.size());
                    f.publish(new DsData.Requests(List.of(row(V1))));
                }
                f.publish(new DsData.Requests(List.of(row(V2))));
                failure(IllegalArgumentException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
            }
        }
    }
    @Test void malformedEventsAndConnectionLossDoNotApplyDecisions() throws Exception {
        try (Fixture f = new Fixture()) {
            f.adapter.malformed = true; f.gateway.incoming(message(MessageType.NOTIFICATION));
            assertTrue(f.errors.poll(3, TimeUnit.SECONDS).contains("No local decision")); assertTrue(f.updates.isEmpty());
            f.gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
            f.gateway.incoming(message(MessageType.REQUEST_UPDATED)); assertTrue(f.updates.isEmpty());
            failure(SecurityException.class, f.gateway.perform(action(DsData.ActionKind.APPROVE, V1)));
        }
    }
    @Test void loginUsesCorrelationAndKeepsDsSeparateFromGnAndCitizen() throws Exception {
        Adapter adapter = new Adapter();
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run, CitizenProtocol.pendingBackend(), GnProtocol.pendingBackend(), adapter)) {
            listener.setSoTimeout(3000); var credentials = new LoginService.Input("127.0.0.1", listener.getLocalPort(), "fixture-ds", "test-only");
            var denied = service.login(credentials);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000); Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream())); var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, "unsolicited", null, JsonNodeFactory.instance.objectNode()));
                JsonUtil.writeMessage(output, new Message(MessageType.ERROR, login.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                denied.get(3, TimeUnit.SECONDS); assertEquals(0, adapter.authorizations.get()); assertTrue(service.dsGateway().isEmpty());
            }
            var pending = service.login(credentials);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000); Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream())); var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, login.correlationId(), null, JsonNodeFactory.instance.objectNode())); pending.get(3, TimeUnit.SECONDS);
                DsGateway gateway = service.dsGateway().orElseThrow(); assertEquals(1, adapter.authorizations.get());
                assertTrue(service.gnGateway().isEmpty()); assertTrue(service.citizenGateway().isEmpty());
                BlockingQueue<DsData.Update> updates = new LinkedBlockingQueue<>();
                gateway.setListener(new DsGateway.Listener() { public void onUpdate(DsData.Update update) { updates.add(update); } });
                adapter.next.set(new DsData.Requests(List.of(row(V1)))); JsonUtil.writeMessage(output, message(MessageType.REQUEST_UPDATED));
                assertInstanceOf(DsData.Requests.class, updates.poll(3, TimeUnit.SECONDS));
                service.disconnect().get(3, TimeUnit.SECONDS); assertTrue(service.dsGateway().isEmpty());
            }
        }
    }
    @Test void localModelsRequireReasonsPreserveOpaqueRevisionsAndSortHistory() {
        assertThrows(IllegalArgumentException.class, () -> new DsData.Action(DsData.ActionKind.REJECT, "r", V1, " "));
        assertThrows(IllegalArgumentException.class, () -> new DsData.Action(DsData.ActionKind.RETURN_TO_GN, "r", V1, ""));
        assertThrows(IllegalArgumentException.class, () -> new DsData.Action(DsData.ActionKind.CHAT, "r", V1, ""));
        assertEquals("", new DsData.Action(DsData.ActionKind.APPROVE, "r", V1, null).note());
        assertEquals(" raw/opaque-revision ", new DsData.Version(" raw/opaque-revision ").value());
        assertThrows(IllegalArgumentException.class, () -> new DsData.Version(" "));
        assertThrows(IllegalArgumentException.class, () -> new DsData.Requests(List.of(row(V1), row(V2))));
        Instant earlier = Instant.EPOCH, later = earlier.plusSeconds(10);
        var details = new DsData.Details("r", V1, "description", "GN officer", List.of(),
                List.of(new DsData.History(later, "backend state B", "later"), new DsData.History(earlier, "backend state A", "earlier")),
                List.of(new DsData.Chat(later, "sender", "later"), new DsData.Chat(earlier, "sender", "earlier")));
        assertEquals("backend state A", details.history().getFirst().status()); assertEquals(earlier, details.messages().getFirst().at());
        assertNull(row(null).submittedAt());
    }
}
