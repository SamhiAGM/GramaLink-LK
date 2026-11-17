package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CitizenGatewayTest {
    private static Message message(MessageType type) { return new Message(type, JsonNodeFactory.instance.objectNode()); }
    private static CitizenData.Draft draft() { return new CitizenData.Draft("local draft type", "local draft division", "Description", null); }

    /** Test-only adapter fixtures exercise client boundaries, not credential verification or a proposed wire schema. */
    private static CitizenProtocol fixture() {
        return new CitizenProtocol() {
            public Optional<CitizenData.Identity> citizenAuthorization(Message reply) {
                return Optional.of(new CitizenData.Identity("fixture-user", "Fixture Citizen"));
            }
            public boolean supports(Operation operation) { return true; }
            public Message createRequest(CitizenData.Draft draft) { return message(MessageType.CREATE_REQUEST); }
            public Message chatMessage(String id, String text) { return message(MessageType.CHAT_MESSAGE); }
        };
    }
    private static <T extends Throwable> T failure(Class<T> type, CompletableFuture<?> future) {
        return assertInstanceOf(type, assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
    }

    @Test void defaultProtocolDeniesClaimedCitizenLoginAndGuardsBothNavigationEntryPoints() throws Exception {
        try (NetworkClient client = new NetworkClient();
             CitizenGateway gateway = new CitizenGateway(client, CitizenProtocol.pendingBackend(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            var claimed = JsonNodeFactory.instance.objectNode().put("role", "CITIZEN").put("userId", "unverified");
            gateway.acceptLoginReply(new Message(MessageType.LOGIN_OK, "claim", "unverified-token", claimed));
            assertFalse(gateway.isCitizenAuthorized());
            assertTrue(gateway.identity().isEmpty());
            assertThrows(SecurityException.class, () -> CitizenDashboardView.open(null, gateway));
            assertThrows(SecurityException.class, () -> new CitizenDashboardController().attach(gateway, () -> { }));
            failure(SecurityException.class, gateway.createRequest(draft()));
            failure(SecurityException.class, gateway.sendMessage("request", "hello"));
        }
    }

    @Test void nonLoginRepliesAndUnsolicitedLoginOkCannotAuthorizeEvenWithFixtureAdapter() {
        try (NetworkClient client = new NetworkClient(); CitizenGateway gateway = new CitizenGateway(client, fixture(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.ERROR));
            assertFalse(gateway.isCitizenAuthorized());
            gateway.incoming(message(MessageType.LOGIN_OK));
            assertFalse(gateway.isCitizenAuthorized());
        }
    }

    @Test void disconnectAndCloseRevokeFixtureAuthorizationAndBlockOperations() throws Exception {
        try (NetworkClient client = new NetworkClient(); CitizenGateway gateway = new CitizenGateway(client, fixture(), Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            assertTrue(gateway.isCitizenAuthorized());
            gateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
            assertFalse(gateway.isCitizenAuthorized());
            failure(SecurityException.class, gateway.sendMessage("request", "hello"));
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            assertFalse(gateway.isCitizenAuthorized(), "A new connection must not inherit identity");
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            gateway.close();
            assertFalse(gateway.isCitizenAuthorized());
            assertTrue(gateway.identity().isEmpty());
        }
    }

    @Test void attachmentInspectionUsesWorkerAndDoesNotUpload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("document.txt");
        Files.writeString(file, "document");
        try (NetworkClient client = new NetworkClient(); CitizenGateway gateway = new CitizenGateway(client, fixture(), Runnable::run)) {
            failure(SecurityException.class, gateway.inspectAttachment(file));
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            var selected = gateway.inspectAttachment(file).get(3, TimeUnit.SECONDS);
            assertEquals("document.txt", selected.fileName());
            assertEquals(Files.size(file), selected.totalBytes());
            assertEquals(file.toAbsolutePath(), selected.path());
            failure(IOException.class, gateway.inspectAttachment(directory));
            failure(IOException.class, gateway.inspectAttachment(directory.resolve("missing")));
            failure(UnsupportedOperationException.class, gateway.createRequest(new CitizenData.Draft("type", "division", "description", selected)));
            assertFalse(client.isConnected(), "File selection must not open a socket");
        }
    }

    @Test void realFramesUseExistingTypesAndAckDoesNotFabricateViewData() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort());
             CitizenGateway gateway = new CitizenGateway(client, fixture(), Runnable::run)) {
            listener.setSoTimeout(3000);
            client.connect();
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(3000);
                var input = new DataInputStream(peer.getInputStream());
                var output = new DataOutputStream(peer.getOutputStream());
                var pending = gateway.createRequest(draft());
                Message sent = JsonUtil.readMessage(input);
                assertEquals(MessageType.CREATE_REQUEST, sent.type());
                assertNotNull(sent.correlationId());
                JsonUtil.writeMessage(output, new Message(MessageType.ERROR, sent.correlationId(), null,
                        JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED")));
                assertEquals("NOT_IMPLEMENTED", pending.get(3, TimeUnit.SECONDS).payload().path("code").asText());
                var chat = gateway.sendMessage("fixture-request", "hello");
                Message chatSent = JsonUtil.readMessage(input);
                assertEquals(MessageType.CHAT_MESSAGE, chatSent.type());
                JsonUtil.writeMessage(output, new Message(MessageType.ACK, chatSent.correlationId(), null, JsonNodeFactory.instance.objectNode()));
                assertEquals(MessageType.ACK, chat.get(3, TimeUnit.SECONDS).type());
                BlockingQueue<CitizenData.Update> updates = new LinkedBlockingQueue<>();
                gateway.setListener(new CitizenGateway.Listener() { public void onUpdate(CitizenData.Update update) { updates.add(update); } });
                gateway.incoming(message(MessageType.ACK));
                assertTrue(updates.isEmpty());
            }
        }
    }

    @Test void unsupportedContractsNeverInvokeEncoder() {
        CitizenProtocol authOnly = new CitizenProtocol() {
            public Optional<CitizenData.Identity> citizenAuthorization(Message reply) { return fixture().citizenAuthorization(reply); }
            public Message createRequest(CitizenData.Draft draft) { throw new AssertionError("Must not encode an unsupported operation"); }
        };
        try (NetworkClient client = new NetworkClient(); CitizenGateway gateway = new CitizenGateway(client, authOnly, Runnable::run)) {
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            assertFalse(gateway.supports(CitizenProtocol.Operation.CREATE_REQUEST));
            failure(UnsupportedOperationException.class, gateway.createRequest(draft()));
            failure(IllegalArgumentException.class, gateway.sendMessage("", "hello"));
        }
    }

    @Test void eventDecodingRunsOffCallerThreadAndMalformedEventsReportError() throws Exception {
        Thread caller = Thread.currentThread();
        BlockingQueue<CitizenData.Update> updates = new LinkedBlockingQueue<>();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        CitizenProtocol adapter = new CitizenProtocol() {
            public Optional<CitizenData.Identity> citizenAuthorization(Message reply) { return fixture().citizenAuthorization(reply); }
            public boolean acceptsEvent(Message message) { return true; }
            public Optional<CitizenData.Update> decodeEvent(Message message) {
                assertNotEquals(caller, Thread.currentThread());
                if (message.type() == MessageType.NOTIFICATION) return Optional.of(new CitizenData.Notifications(List.of()));
                throw new IllegalArgumentException("Malformed fixture");
            }
        };
        try (NetworkClient client = new NetworkClient(); CitizenGateway gateway = new CitizenGateway(client, adapter, Runnable::run)) {
            gateway.setListener(new CitizenGateway.Listener() {
                public void onUpdate(CitizenData.Update update) { updates.add(update); }
                public void onError(String message) { errors.add(message); }
            });
            gateway.connectionStatus(NetworkClient.Status.CONNECTED);
            gateway.acceptLoginReply(message(MessageType.LOGIN_OK));
            gateway.incoming(message(MessageType.NOTIFICATION));
            assertInstanceOf(CitizenData.Notifications.class, updates.poll(3, TimeUnit.SECONDS));
            gateway.incoming(message(MessageType.REQUEST_UPDATED));
            assertEquals("The server event could not be read.", errors.poll(3, TimeUnit.SECONDS));
        }
    }

    @Test void localModelsValidateDraftsAndSortHistoryWithoutInventingStatuses() {
        assertThrows(IllegalArgumentException.class, () -> new CitizenData.Draft("", "division", "text", null));
        assertThrows(IllegalArgumentException.class, () -> new CitizenData.Draft("type", "", "text", null));
        assertThrows(IllegalArgumentException.class, () -> new CitizenData.Draft("type", "division", " ", null));
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z"), later = earlier.plusSeconds(60);
        var details = new CitizenData.Details("request", "description", List.of(
                new CitizenData.StatusEntry(later, "backend status B", "later"),
                new CitizenData.StatusEntry(earlier, "backend status A", "earlier")), List.of(
                new CitizenData.ChatEntry(later, "sender", "later"), new CitizenData.ChatEntry(earlier, "sender", "earlier")));
        assertEquals(earlier, details.timeline().getFirst().at());
        assertEquals("backend status A", details.timeline().getFirst().status());
        assertEquals(earlier, details.messages().getFirst().at());
        var notifications = new CitizenData.Notifications(List.of(new CitizenData.Notification(earlier, "old", null), new CitizenData.Notification(later, "new", null)));
        assertEquals("new", notifications.entries().getFirst().text());
        assertEquals(0.5, new CitizenData.TransferProgress(5, 10, "TRANSPORT_RECEIVED").fraction());
        assertEquals(0, new CitizenData.TransferProgress(0, 0, "READY").fraction());
        assertThrows(IllegalArgumentException.class, () -> new CitizenData.TransferProgress(11, 10, "invalid"));
    }
}
