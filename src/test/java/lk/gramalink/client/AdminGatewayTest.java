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
class AdminGatewayTest {
    private static Message message(MessageType type) { return new Message(type, JsonNodeFactory.instance.objectNode()); }
    private static AdminData.UserDraft user() { return new AdminData.UserDraft("fixture.user", "Fixture User", OfflineAdminWorkspace.ROLE, null); }
    private static AdminData.OfficeDraft office() { return new AdminData.OfficeDraft("Fixture office", OfflineAdminWorkspace.GN, "Fixture area"); }
    private static AdminData.Command createUser() { return new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER, null, user(), null, ""); }
    private static <T extends Throwable> T failure(Class<T> type, CompletableFuture<?> future) {
        return assertInstanceOf(type, assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
    }
    /** Test adapter only: empty test envelopes are not proposed admin messages, schemas or authentication. */
    private static class Adapter implements AdminProtocol {
        final AtomicInteger validations = new AtomicInteger();
        final AtomicReference<AdminData.Update> next = new AtomicReference<>();
        final List<AdminData.Command> encoded = new CopyOnWriteArrayList<>();
        Set<MessageType> declaredTypes = Set.of(MessageType.STATUS_UPDATE);
        MessageType testWireType = MessageType.STATUS_UPDATE;
        volatile Thread decoderThread;
        boolean malformed;
        public Optional<AdminData.Identity> administratorAuthorization(Message reply) { validations.incrementAndGet(); return Optional.of(new AdminData.Identity("fixture-admin", "Fixture Administrator")); }
        public boolean supports(AdminData.Operation operation) { return true; }
        public Set<MessageType> outboundTypes(AdminData.Operation operation) { return declaredTypes; }
        public Set<MessageType> eventTypes() { return Set.of(MessageType.NOTIFICATION); }
        public Message encode(AdminData.Command command) { encoded.add(command); return message(testWireType); }
        public Optional<AdminData.Update> decodeEvent(Message message) {
            decoderThread = Thread.currentThread(); if (malformed) throw new IllegalArgumentException("Malformed fixture"); return Optional.ofNullable(next.getAndSet(null));
        }
    }
    private static final class Fixture implements AutoCloseable {
        final NetworkClient client;
        final Adapter adapter = new Adapter();
        final AdminGateway gateway;
        final BlockingQueue<AdminData.Update> updates = new LinkedBlockingQueue<>();
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        Fixture() { this(new NetworkClient()); }
        Fixture(NetworkClient client) {
            this.client = client; gateway = new AdminGateway(client, adapter, Runnable::run);
            gateway.setListener(new AdminGateway.Listener() {
                public void onUpdate(AdminData.Update update) { updates.add(update); }
                public void onError(String message) { errors.add(message); }
            });
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
        }
        void publish(AdminData.Update update) throws Exception {
            adapter.next.set(update); gateway.incoming(message(MessageType.NOTIFICATION)); assertSame(update, updates.poll(3, TimeUnit.SECONDS));
        }
        void data() throws Exception {
            publish(new AdminData.Catalogues(List.of(OfflineAdminWorkspace.ROLE), List.of(OfflineAdminWorkspace.GN, OfflineAdminWorkspace.DS)));
            publish(OfflineAdminWorkspace.offices()); publish(OfflineAdminWorkspace.users());
        }
        public void close() { gateway.close(); client.close(); }
    }
    @Test void pendingContractRejectsAllRoleClaimsAndBothNavigationEntries() throws Exception {
        try (NetworkClient client = new NetworkClient(); AdminGateway gateway = new AdminGateway(client, AdminProtocol.pendingBackend(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            for (String role : List.of("ADMIN", "ADMINISTRATOR", "DS_OFFICER", "GN_OFFICER", "CITIZEN")) {
                gateway.acceptLoginReply(new Message(MessageType.LOGIN_OK, "claim", "unverified", JsonNodeFactory.instance.objectNode().put("role", role).put("userId", "claimed")));
                assertFalse(gateway.isAdministratorAuthorized()); assertTrue(gateway.identity().isEmpty());
            }
            assertThrows(SecurityException.class, () -> AdminDashboardView.open(null, gateway));
            assertThrows(SecurityException.class, () -> new AdminDashboardController().attach(gateway, () -> { }));
            failure(SecurityException.class, gateway.perform(createUser()));
        }
    }
    @Test void unsolicitedLoginAndReconnectCannotGrantOrInheritAdministratorAccess() {
        Adapter adapter = new Adapter();
        try (NetworkClient client = new NetworkClient(); AdminGateway gateway = new AdminGateway(client, adapter, Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.incoming(message(MessageType.LOGIN_OK)); assertFalse(gateway.isAdministratorAuthorized()); assertEquals(0, adapter.validations.get());
            gateway.acceptLoginReply(message(MessageType.ERROR)); assertFalse(gateway.isAdministratorAuthorized());
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); assertTrue(gateway.isAdministratorAuthorized());
            gateway.connectionStatus(NetworkClient.Status.DISCONNECTED); gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            assertFalse(gateway.isAdministratorAuthorized()); failure(SecurityException.class, gateway.perform(createUser()));
        }
    }
    @Test void declaredCapabilityWithoutAnAgreedWireTypeRemainsDisabled() {
        try (Fixture f = new Fixture()) {
            f.adapter.declaredTypes = Set.of();
            for (AdminData.Operation operation : AdminData.Operation.values()) assertFalse(f.gateway.supports(operation));
            failure(UnsupportedOperationException.class, f.gateway.perform(createUser())); assertTrue(f.adapter.encoded.isEmpty());
        }
    }
    @Test void gatewayRejectsNonDemoTargetsAndUnrecognizedCataloguesBeforeEncoding() throws Exception {
        try (Fixture f = new Fixture()) {
            f.data();
            failure(SecurityException.class, f.gateway.perform(new AdminData.Command(AdminData.Operation.UPDATE_DEMO_USER, "PREVIEW-USER-READONLY", user(), null, "")));
            failure(SecurityException.class, f.gateway.perform(new AdminData.Command(AdminData.Operation.DISABLE_DEMO_USER, "unknown", null, null, "reason")));
            failure(SecurityException.class, f.gateway.perform(new AdminData.Command(AdminData.Operation.UPDATE_DEMO_OFFICE, "PREVIEW-OFFICE-READONLY", null, office(), "")));
            var unknownRole = new AdminData.UserDraft("u", "name", new AdminData.Choice("local-admin-claim", "Untrusted role"), null);
            failure(IllegalArgumentException.class, f.gateway.perform(new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER, null, unknownRole, null, "")));
            var unknownOffice = new AdminData.UserDraft("u", "name", OfflineAdminWorkspace.ROLE, "not-loaded");
            failure(IllegalArgumentException.class, f.gateway.perform(new AdminData.Command(AdminData.Operation.CREATE_DEMO_USER, null, unknownOffice, null, "")));
            assertTrue(f.adapter.encoded.isEmpty());
        }
    }
    @Test void disallowedTransportAndMismatchedWireTypesAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.data(); f.adapter.declaredTypes = Set.of(MessageType.LOGIN); f.adapter.testWireType = MessageType.LOGIN;
            failure(IllegalArgumentException.class, f.gateway.perform(createUser()));
            f.adapter.declaredTypes = Set.of(MessageType.STATUS_UPDATE); f.adapter.testWireType = MessageType.CHAT_MESSAGE;
            failure(IllegalArgumentException.class, f.gateway.perform(createUser()));
        }
    }
    @Test void transportFixtureCorrelatesRepliesWithoutGeneratingAdministrativeData() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort())) {
            listener.setSoTimeout(3000); client.connect();
            try (Socket peer = listener.accept(); Fixture f = new Fixture(client)) {
                peer.setSoTimeout(3000); var input = new DataInputStream(peer.getInputStream()); var output = new DataOutputStream(peer.getOutputStream()); f.data();
                for (AdminData.Operation operation : AdminData.Operation.values()) {
                    String id = operation.create() ? null : operation.userOperation() ? "PREVIEW-USER-001" : "PREVIEW-GN-DEMO";
                    AdminData.UserDraft userDraft = operation == AdminData.Operation.CREATE_DEMO_USER || operation == AdminData.Operation.UPDATE_DEMO_USER ? user() : null;
                    AdminData.OfficeDraft officeDraft = operation == AdminData.Operation.CREATE_DEMO_OFFICE || operation == AdminData.Operation.UPDATE_DEMO_OFFICE ? office() : null;
                    var future = f.gateway.perform(new AdminData.Command(operation, id, userDraft, officeDraft, "Fixture reason"));
                    Message sent = JsonUtil.readMessage(input); assertNotNull(sent.correlationId());
                    JsonUtil.writeMessage(output, new Message(MessageType.ACK, sent.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                    assertEquals(MessageType.ACK, future.get(3, TimeUnit.SECONDS).type());
                }
                assertEquals(6, f.adapter.encoded.size()); assertTrue(f.updates.isEmpty(), "ACK must not produce users, offices, audit entries or statistics");
            }
        }
    }
    @Test void decodingUsesWorkerAndMalformedOrLateEventsCannotChangeViews() throws Exception {
        try (Fixture f = new Fixture()) {
            f.publish(new AdminData.Overview(null, null, null, null, null)); assertNotEquals(Thread.currentThread(), f.adapter.decoderThread);
            f.adapter.malformed = true; f.gateway.incoming(message(MessageType.NOTIFICATION));
            assertTrue(f.errors.poll(3, TimeUnit.SECONDS).contains("No local record or statistic")); assertTrue(f.updates.isEmpty());
            f.gateway.close(); f.gateway.incoming(message(MessageType.NOTIFICATION)); assertTrue(f.updates.isEmpty());
            failure(SecurityException.class, f.gateway.perform(createUser()));
        }
    }
    @Test void loginRequiresCorrelationAndAdminEventsStayOnTheSameSocket() throws Exception {
        Adapter adapter = new Adapter();
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run, CitizenProtocol.pendingBackend(), GnProtocol.pendingBackend(), DsProtocol.pendingBackend(), adapter)) {
            listener.setSoTimeout(3000); var credentials = new LoginService.Input("127.0.0.1", listener.getLocalPort(), "fixture-admin", "test-only");
            var denied = service.login(credentials);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000); Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream())); var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, "unsolicited", null, JsonNodeFactory.instance.objectNode()));
                JsonUtil.writeMessage(output, new Message(MessageType.ERROR, login.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                denied.get(3, TimeUnit.SECONDS); assertEquals(0, adapter.validations.get()); assertTrue(service.adminGateway().isEmpty());
            }
            var acceptedByFixture = service.login(credentials);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000); Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream())); var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, login.correlationId(), null, JsonNodeFactory.instance.objectNode())); acceptedByFixture.get(3, TimeUnit.SECONDS);
                AdminGateway gateway = service.adminGateway().orElseThrow(); assertEquals(1, adapter.validations.get());
                assertTrue(service.citizenGateway().isEmpty()); assertTrue(service.gnGateway().isEmpty()); assertTrue(service.dsGateway().isEmpty());
                BlockingQueue<AdminData.Update> updates = new LinkedBlockingQueue<>(); gateway.setListener(new AdminGateway.Listener() { public void onUpdate(AdminData.Update update) { updates.add(update); } });
                adapter.next.set(new AdminData.Clients(List.of())); JsonUtil.writeMessage(output, message(MessageType.NOTIFICATION));
                assertInstanceOf(AdminData.Clients.class, updates.poll(3, TimeUnit.SECONDS)); service.disconnect().get(3, TimeUnit.SECONDS); assertTrue(service.adminGateway().isEmpty());
            }
        }
    }
    @Test void localDraftsCountsAndAuditModelsValidateWithoutInventingStatistics() {
        assertThrows(IllegalArgumentException.class, () -> new AdminData.UserDraft("", "name", OfflineAdminWorkspace.ROLE, null));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.UserDraft("user", "name", null, null));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.OfficeDraft("name", OfflineAdminWorkspace.GN, ""));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.Command(AdminData.Operation.DISABLE_DEMO_USER, "id", null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.Command(AdminData.Operation.ARCHIVE_DEMO_OFFICE, "id", null, null, ""));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.Overview(-1L, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AdminData.StatusCount("backend state", -1));
        assertNull(new AdminData.Overview(null, null, null, null, null).connectedClients());
        var statistics = new AdminData.Statistics(List.of(new AdminData.StatusCount("Backend-defined state", 2)), 40L, "Partial", null);
        assertEquals(40L, statistics.total()); assertEquals("Backend-defined state", statistics.rows().getFirst().status());
        Instant early = Instant.EPOCH, later = early.plusSeconds(5);
        var audit = new AdminData.Audit(List.of(new AdminData.AuditEntry(null, "u", "unknown", null, null), new AdminData.AuditEntry(early, "u", "early", null, null), new AdminData.AuditEntry(later, "u", "later", null, null)));
        assertEquals("later", audit.rows().getFirst().activity()); assertNull(audit.rows().getLast().at());
    }
}
