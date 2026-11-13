package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GnGatewayTest {
    private static Message message(MessageType type) { return new Message(type, JsonNodeFactory.instance.objectNode()); }
    /** Tests only. This exercises an adapter boundary, not a real authentication backend or JSON schema. */
    private static GnProtocol fixture() {
        return new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { return Optional.of(new GnData.Identity("fixture-officer", "Fixture officer")); }
            public boolean supports(GnData.ActionKind kind) { return true; }
            public Message encode(GnData.Action action) { return message(action.kind() == GnData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE); }
        };
    }
    private static GnData.Action reject() { return new GnData.Action(GnData.ActionKind.REJECT_REQUEST, "fixture-request", null, "Fixture reason"); }
    private static <T extends Throwable> T failure(Class<T> type, CompletableFuture<?> future) {
        return assertInstanceOf(type, assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
    }

    @Test void defaultContractRejectsClaimedGnCitizenAndDsIdentitiesAndGuardsNavigation() throws Exception {
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, GnProtocol.pendingBackend(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            for (String role : List.of("GN_OFFICER", "GN", "CITIZEN", "DS_OFFICER")) {
                gateway.acceptLoginReply(new Message(MessageType.LOGIN_OK, "claimed", "unverified-token",
                        JsonNodeFactory.instance.objectNode().put("role", role).put("userId", "unverified")));
                assertFalse(gateway.isGnAuthorized());
                assertTrue(gateway.identity().isEmpty());
            }
            assertThrows(SecurityException.class, () -> GnDashboardView.open(null, gateway));
            assertThrows(SecurityException.class, () -> new GnDashboardController().attach(gateway, () -> { }));
            failure(SecurityException.class, gateway.perform(reject()));
        }
    }

    @Test void unsolicitedLoginAndErrorsCannotAuthorizeAndReconnectDoesNotInheritAccess() {
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, fixture(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.incoming(message(MessageType.LOGIN_OK));
            assertFalse(gateway.isGnAuthorized());
            gateway.acceptLoginReply(message(MessageType.ERROR)); assertFalse(gateway.isGnAuthorized());
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK)); assertTrue(gateway.isGnAuthorized());
            gateway.connectionStatus(NetworkClient.Status.DISCONNECTED); assertFalse(gateway.isGnAuthorized());
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); assertFalse(gateway.isGnAuthorized());
            failure(SecurityException.class, gateway.perform(reject()));
            gateway.close(); assertFalse(gateway.isGnAuthorized());
        }
    }

    @Test void unsupportedOfficerActionNeverCallsAnEncoder() {
        GnProtocol authOnly = new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { return fixture().gnAuthorization(reply); }
            public Message encode(GnData.Action action) { throw new AssertionError("Unsupported action must not be encoded"); }
        };
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, authOnly, Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            for (GnData.ActionKind kind : GnData.ActionKind.values()) assertFalse(gateway.supports(kind));
            failure(UnsupportedOperationException.class, gateway.perform(reject()));
            assertFalse(client.isConnected());
        }
    }

    @Test void realSocketFramesUseOnlyExistingTypesAndCorrelateBackendErrors() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort());
             GnGateway gateway = new GnGateway(client, fixture(), Runnable::run)) {
            listener.setSoTimeout(3000); client.connect();
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                var input = new DataInputStream(peer.getInputStream()); var output = new DataOutputStream(peer.getOutputStream());
                for (GnData.ActionKind kind : GnData.ActionKind.values()) {
                    var pending = gateway.perform(new GnData.Action(kind, "fixture-request", "fixture-document", "Fixture note"));
                    Message sent = JsonUtil.readMessage(input);
                    assertEquals(kind == GnData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE, sent.type());
                    assertNotNull(sent.correlationId());
                    JsonUtil.writeMessage(output, new Message(MessageType.ERROR, sent.correlationId(), null,
                            JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED")));
                    assertEquals("NOT_IMPLEMENTED", pending.get(3, TimeUnit.SECONDS).payload().path("code").asText());
                }
            }
        }
    }

    @Test void incorrectWireMappingIsRejectedBeforeSending() {
        GnProtocol adapter = new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { return fixture().gnAuthorization(reply); }
            public boolean supports(GnData.ActionKind kind) { return true; }
            public Message encode(GnData.Action action) { return message(MessageType.LOGIN); }
        };
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, adapter, Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            failure(IllegalArgumentException.class, gateway.perform(reject()));
        }
    }

    @Test void updatesDecodeOffCallerThreadAndLateUpdatesAreDroppedAfterClose() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch decoding = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        BlockingQueue<GnData.Update> updates = new LinkedBlockingQueue<>();
        GnProtocol adapter = new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { return fixture().gnAuthorization(reply); }
            public Optional<GnData.Update> decodeEvent(Message message) {
                assertNotEquals(caller, Thread.currentThread()); decoding.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new RuntimeException(error); }
                finally { finished.countDown(); }
                return Optional.of(new GnData.Requests(List.of()));
            }
        };
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, adapter, Runnable::run)) {
            gateway.setListener(new GnGateway.Listener() { public void onUpdate(GnData.Update update) { updates.add(update); } });
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            gateway.incoming(message(MessageType.REQUEST_UPDATED)); assertTrue(decoding.await(3, TimeUnit.SECONDS));
            gateway.close(); release.countDown(); assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertTrue(updates.isEmpty());
        } finally { release.countDown(); }
    }

    @Test void malformedEventsReportAnErrorWithoutApplyingAnUpdate() throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        GnProtocol adapter = new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { return fixture().gnAuthorization(reply); }
            public Optional<GnData.Update> decodeEvent(Message message) { throw new IllegalArgumentException("Malformed fixture"); }
        };
        try (NetworkClient client = new NetworkClient(); GnGateway gateway = new GnGateway(client, adapter, Runnable::run)) {
            gateway.setListener(new GnGateway.Listener() { public void onError(String error) { errors.add(error); } });
            gateway.connectionStatus(NetworkClient.Status.CONNECTED); gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            gateway.incoming(message(MessageType.NOTIFICATION));
            assertTrue(errors.poll(3, TimeUnit.SECONDS).contains("No local status change"));
        }
    }

    @Test void validatesOfficerIntentWithoutInventingStatusesAndSortsHistory() {
        assertThrows(IllegalArgumentException.class, () -> new GnData.Action(GnData.ActionKind.REJECT_REQUEST, "r", null, " "));
        assertThrows(IllegalArgumentException.class, () -> new GnData.Action(GnData.ActionKind.REQUEST_INFORMATION, "r", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new GnData.Action(GnData.ActionKind.VERIFY_DOCUMENT, "r", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new GnData.Action(GnData.ActionKind.CHAT, "r", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new GnData.Action(GnData.ActionKind.FORWARD_TO_DS, "", null, ""));
        assertEquals("", new GnData.Action(GnData.ActionKind.FORWARD_TO_DS, "r", null, null).note());
        assertEquals("reason", new GnData.Action(GnData.ActionKind.REJECT_REQUEST, "r", null, " reason ").note());
        Instant earlier = Instant.EPOCH, later = earlier.plusSeconds(60);
        var detail = new GnData.Details("r", "description", List.of(), List.of(new GnData.History(later, "backend-B", "later"),
                new GnData.History(earlier, "backend-A", "earlier")), List.of(new GnData.Chat(later, "sender", "later"), new GnData.Chat(earlier, "sender", "earlier")));
        assertEquals("backend-A", detail.history().getFirst().status());
        assertEquals(earlier, detail.messages().getFirst().at());
        var notifications = new GnData.Notifications(List.of(new GnData.Notification(earlier, "old", null), new GnData.Notification(later, "new", null)));
        assertEquals("new", notifications.entries().getFirst().text());
    }

    @Test void loginBindingRequiresCorrelationAndUsesTheSameSocketForGnEvents() throws Exception {
        java.util.concurrent.atomic.AtomicInteger validations = new java.util.concurrent.atomic.AtomicInteger();
        GnProtocol adapter = new GnProtocol() {
            public Optional<GnData.Identity> gnAuthorization(Message reply) { validations.incrementAndGet(); return fixture().gnAuthorization(reply); }
            public Optional<GnData.Update> decodeEvent(Message message) { return Optional.of(new GnData.Notifications(List.of())); }
        };
        try (ServerSocket listener = new ServerSocket(0);
             LoginService service = new LoginService(status -> { }, Runnable::run, CitizenProtocol.pendingBackend(), adapter)) {
            listener.setSoTimeout(3000);
            var input = new LoginService.Input("127.0.0.1", listener.getLocalPort(), "fixture-officer", "test-only");
            var denied = service.login(input);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, "unsolicited", null, JsonNodeFactory.instance.objectNode()));
                JsonUtil.writeMessage(output, new Message(MessageType.ERROR, login.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                assertEquals(MessageType.ERROR, denied.get(3, TimeUnit.SECONDS).type());
                assertEquals(0, validations.get()); assertTrue(service.gnGateway().isEmpty());
            }
            var acceptedByTestAdapter = service.login(input);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                Message login = JsonUtil.readMessage(new DataInputStream(peer.getInputStream()));
                var output = new DataOutputStream(peer.getOutputStream());
                JsonUtil.writeMessage(output, new Message(MessageType.LOGIN_OK, login.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                acceptedByTestAdapter.get(3, TimeUnit.SECONDS);
                GnGateway gateway = service.gnGateway().orElseThrow();
                assertEquals(1, validations.get()); assertTrue(service.citizenGateway().isEmpty());
                BlockingQueue<GnData.Update> updates = new LinkedBlockingQueue<>();
                gateway.setListener(new GnGateway.Listener() { public void onUpdate(GnData.Update update) { updates.add(update); } });
                JsonUtil.writeMessage(output, message(MessageType.NOTIFICATION));
                assertInstanceOf(GnData.Notifications.class, updates.poll(3, TimeUnit.SECONDS));
                service.disconnect().get(3, TimeUnit.SECONDS);
                assertTrue(service.gnGateway().isEmpty());
            }
        }
    }
}
