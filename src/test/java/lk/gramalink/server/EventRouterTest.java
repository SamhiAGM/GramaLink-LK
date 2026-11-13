package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventRouterTest {
    private static Message event(MessageType type) {
        return new Message(type, "event-17", null, JsonNodeFactory.instance.objectNode()
                .put("requestId", "request-42").put("text", "Private event"));
    }

    private static void register(SessionManager sessions, String userId, Socket socket) {
        // Test-only stand-in for a future successful backend authentication result.
        sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity(userId, "CITIZEN"), socket);
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class,
            names = {"NEW_REQUEST", "REQUEST_UPDATED", "CHAT_MESSAGE", "NOTIFICATION"})
    void deliversEachSupportedEventAsAFramedMessage(MessageType type) throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair recipient = new SocketPair()) {
            register(sessions, "recipient", recipient.server);
            Message original = event(type);

            EventRouter.DeliveryResult result = router.sendToUser("recipient", original);

            assertEquals("recipient", result.userId());
            assertEquals(1, result.deliveredConnections());
            assertEquals(0, result.failedConnections());
            assertFalse(result.offline());
            assertEquals(original, recipient.read());
        }
    }

    @Test
    void sendsOnlyToTheExplicitRecipientsAndTheirAuthenticatedConnections() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair();
             SocketPair unrelated = new SocketPair(); SocketPair anonymous = new SocketPair()) {
            register(sessions, "first", first.server);
            register(sessions, "second", second.server);
            register(sessions, "unrelated", unrelated.server);
            Message original = event(MessageType.NEW_REQUEST);

            List<EventRouter.DeliveryResult> results = router.sendToUsers(List.of("first", "second"), original);

            assertEquals(List.of("first", "second"), results.stream().map(EventRouter.DeliveryResult::userId).toList());
            assertTrue(results.stream().allMatch(result -> result.deliveredConnections() == 1
                    && result.failedConnections() == 0 && !result.offline()));
            assertEquals(original, first.read());
            assertEquals(original, second.read());
            unrelated.assertNoMessage();
            anonymous.assertNoMessage();
        }
    }

    @Test
    void sendsToAllConnectionsForOneUser() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair()) {
            register(sessions, "same-user", first.server);
            register(sessions, "same-user", second.server);
            Message original = event(MessageType.REQUEST_UPDATED);

            EventRouter.DeliveryResult result = router.sendToUser("same-user", original);

            assertEquals(2, result.deliveredConnections());
            assertEquals(0, result.failedConnections());
            assertEquals(original, first.read());
            assertEquals(original, second.read());
        }
    }

    @Test
    void reportsOfflineRecipientWithoutBlockingAnotherRecipient() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair online = new SocketPair()) {
            register(sessions, "online", online.server);
            Message original = event(MessageType.CHAT_MESSAGE);

            List<EventRouter.DeliveryResult> results = router.sendToUsers(List.of("offline", "online"), original);

            assertEquals(new EventRouter.DeliveryResult("offline", 0, 0), results.getFirst());
            assertTrue(results.getFirst().offline());
            assertEquals(1, results.get(1).deliveredConnections());
            assertEquals(original, online.read());
        }
    }

    @Test
    void closedConnectionIsReportedOfflineAndRemovedFromSessions() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair recipient = new SocketPair()) {
            register(sessions, "recipient", recipient.server);
            recipient.server.close();

            EventRouter.DeliveryResult result = router.sendToUser("recipient", event(MessageType.NEW_REQUEST));

            assertTrue(result.offline());
            assertEquals(0, result.deliveredConnections());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void disconnectionDuringAWriteIsReportedAndDoesNotPreventOtherRecipients() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (FailingSocket disconnected = new FailingSocket(); SocketPair online = new SocketPair()) {
            register(sessions, "disconnecting", disconnected);
            register(sessions, "online", online.server);
            Message original = event(MessageType.REQUEST_UPDATED);

            List<EventRouter.DeliveryResult> results = router.sendToUsers(List.of("disconnecting", "online"), original);

            assertEquals(0, results.getFirst().deliveredConnections());
            assertEquals(1, results.getFirst().failedConnections());
            assertFalse(results.getFirst().offline());
            assertTrue(disconnected.isClosed());
            assertFalse(sessions.isOnline("disconnecting"));
            assertEquals(1, results.get(1).deliveredConnections());
            assertEquals(original, online.read());
        }
    }

    @Test
    void oneFailedConnectionDoesNotPreventDeliveryToAnotherConnectionOfTheSameUser() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (FailingSocket disconnected = new FailingSocket(); SocketPair online = new SocketPair()) {
            register(sessions, "same-user", disconnected);
            register(sessions, "same-user", online.server);
            Message original = event(MessageType.NOTIFICATION);

            EventRouter.DeliveryResult result = router.sendToUser("same-user", original);

            assertEquals(1, result.deliveredConnections());
            assertEquals(1, result.failedConnections());
            assertFalse(result.offline());
            assertEquals(original, online.read());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.isOnline("same-user"));
        }
    }

    @Test
    void duplicateRecipientIdsAreDeliveredOnceAndReportsAreImmutable() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair first = new SocketPair(); SocketPair second = new SocketPair()) {
            register(sessions, "first", first.server);
            register(sessions, "second", second.server);
            Message original = event(MessageType.NOTIFICATION);

            List<EventRouter.DeliveryResult> results = router.sendToUsers(List.of("second", "first", "second"), original);

            assertEquals(List.of("second", "first"), results.stream().map(EventRouter.DeliveryResult::userId).toList());
            assertThrows(UnsupportedOperationException.class, () -> results.add(results.getFirst()));
            assertEquals(original, first.read());
            assertEquals(original, second.read());
            first.assertNoMessage();
            second.assertNoMessage();
        }
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class,
            names = {"LOGIN", "LOGIN_OK", "CREATE_REQUEST", "STATUS_UPDATE", "FILE_META", "FILE_CHUNK",
                    "HEARTBEAT", "ACK", "ERROR"})
    void rejectsNonEventMessageTypes(MessageType type) {
        var router = new EventRouter(new SessionManager());
        assertThrows(IllegalArgumentException.class, () -> router.sendToUser("recipient", event(type)));
    }

    @Test
    void rejectsEventTokensBeforeWritingToRecipients() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair recipient = new SocketPair()) {
            register(sessions, "recipient", recipient.server);
            Message tokenBearing = new Message(MessageType.NEW_REQUEST, null, "private-auth-token",
                    JsonNodeFactory.instance.objectNode());

            assertThrows(IllegalArgumentException.class, () -> router.sendToUser("recipient", tokenBearing));
            recipient.assertNoMessage();
            assertEquals(1, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void validatesAllRecipientIdsBeforeSendingAnyEvents() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair recipient = new SocketPair()) {
            register(sessions, "recipient", recipient.server);
            assertThrows(IllegalArgumentException.class,
                    () -> router.sendToUsers(List.of("recipient", " "), event(MessageType.NEW_REQUEST)));
            assertThrows(NullPointerException.class,
                    () -> router.sendToUsers(Arrays.asList("recipient", null), event(MessageType.NEW_REQUEST)));
            recipient.assertNoMessage();
        }
    }

    @Test
    void oversizedEventWritesNothingAndLeavesRecipientUsable() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (SocketPair recipient = new SocketPair()) {
            register(sessions, "recipient", recipient.server);
            Message oversized = new Message(MessageType.NEW_REQUEST,
                    JsonNodeFactory.instance.objectNode().put("text", "x".repeat(JsonUtil.MAX_PAYLOAD_BYTES)));

            assertThrows(IOException.class, () -> router.sendToUser("recipient", oversized));
            recipient.assertNoMessage();
            assertEquals(1, sessions.getAuthenticatedSessionCount());

            Message valid = event(MessageType.CHAT_MESSAGE);
            assertEquals(1, router.sendToUser("recipient", valid).deliveredConnections());
            assertEquals(valid, recipient.read());
        }
    }

    @Test
    void anEmptyRecipientListProducesNoDeliveries() throws IOException {
        assertTrue(new EventRouter(new SessionManager())
                .sendToUsers(List.of(), event(MessageType.NEW_REQUEST)).isEmpty());
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;
        private final DataInputStream input;

        private SocketPair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                server = listener.accept();
            }
            client.setSoTimeout(5000);
            input = new DataInputStream(client.getInputStream());
        }

        private Message read() throws IOException {
            client.setSoTimeout(5000);
            return JsonUtil.readMessage(input);
        }

        private void assertNoMessage() throws IOException {
            client.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, input::read);
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

    /** Deterministic test-only socket that disconnects when the first write starts. */
    private static final class FailingSocket extends Socket {
        private boolean closed;

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    closed = true;
                    throw new IOException("Simulated client disconnection during write.");
                }
            };
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
