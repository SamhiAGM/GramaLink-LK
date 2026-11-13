package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleTest {
    // Test fixture representing a successful future backend verification only.
    private static final SessionManager.VerifiedIdentity VERIFIED_USER =
            new SessionManager.VerifiedIdentity("verified-user-1", "CITIZEN");

    @Test
    void forgedLoginAndClientLoginOkCannotCreateSessions() throws Exception {
        var sessions = new SessionManager();
        try (Connection connection = new Connection(sessions)) {
            for (MessageType type : new MessageType[]{MessageType.LOGIN, MessageType.LOGIN_OK}) {
                var forgedClaims = JsonNodeFactory.instance.objectNode()
                        .put("userId", "administrator")
                        .put("username", "administrator")
                        .put("role", "ADMIN")
                        .put("token", "unverified-payload-token");
                Message response = connection.exchange(new Message(type, "forged-" + type,
                        "unverified-envelope-token", forgedClaims));

                assertNotImplemented(response);
                assertEquals("forged-" + type, response.correlationId());
                assertNull(response.token());
                assertEquals(0, sessions.getAuthenticatedSessionCount());
                assertFalse(sessions.isOnline("administrator"));
            }
        }
    }

    @Test
    void peerEofRemovesOnlyItsSessionUntilTheLastConnectionDisconnects() throws Exception {
        var sessions = new SessionManager();
        try (Connection first = new Connection(sessions); Connection second = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, first.serverSide);
            sessions.registerVerifiedSession(VERIFIED_USER, second.serverSide);
            assertEquals(2, sessions.getAuthenticatedSessionCount());

            first.client.shutdownOutput();
            assertNull(JsonUtil.readMessage(first.input));
            first.assertStopped();

            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.isOnline(VERIFIED_USER.userId()));
            assertEquals(second.serverSide,
                    sessions.findOnlineSessions(VERIFIED_USER.userId()).getFirst().connection());
            assertHeartbeatAcknowledged(second.exchange(new Message(MessageType.HEARTBEAT,
                    JsonNodeFactory.instance.objectNode())));

            second.client.shutdownOutput();
            assertNull(JsonUtil.readMessage(second.input));
            second.assertStopped();
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertFalse(sessions.isOnline(VERIFIED_USER.userId()));
        }
    }

    @Test
    void malformedFrameCleansItsSessionWithoutAffectingAnotherConnection() throws Exception {
        var sessions = new SessionManager();
        try (Connection malformed = new Connection(sessions); Connection healthy = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, malformed.serverSide);
            sessions.registerVerifiedSession(VERIFIED_USER, healthy.serverSide);

            malformed.output.writeInt(0);
            malformed.output.flush();
            assertEquals(-1, malformed.input.read());
            malformed.assertStopped();

            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.isOnline(VERIFIED_USER.userId()));
            assertNotImplemented(healthy.exchange(new Message(MessageType.ACK,
                    JsonNodeFactory.instance.objectNode())));
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    @Test
    void serverSideClosureUnblocksPendingReadAndRemovesSession() throws Exception {
        var sessions = new SessionManager();
        try (Connection connection = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, connection.serverSide);
            assertHeartbeatAcknowledged(connection.exchange(new Message(MessageType.HEARTBEAT,
                    JsonNodeFactory.instance.objectNode())));

            connection.output.writeInt(100);
            connection.output.writeByte('{');
            connection.output.flush();
            connection.serverSide.close();
            connection.assertStopped();

            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertFalse(sessions.isOnline(VERIFIED_USER.userId()));
        }
    }

    private static void assertNotImplemented(Message response) {
        assertNotNull(response);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals("NOT_IMPLEMENTED", response.payload().path("code").asText());
    }

    private static void assertHeartbeatAcknowledged(Message response) {
        assertNotNull(response);
        assertEquals(MessageType.ACK, response.type());
        assertEquals("HEARTBEAT", response.payload().path("acknowledgedType").asText());
    }

    private static final class Connection implements AutoCloseable {
        private final Socket client;
        private final Socket serverSide;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread handler;

        private Connection(SessionManager sessions) throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                serverSide = listener.accept();
            }
            client.setSoTimeout(5000);
            input = new DataInputStream(client.getInputStream());
            output = new DataOutputStream(client.getOutputStream());
            handler = new Thread(() -> {
                try {
                    new ClientHandler(serverSide, sessions, disconnected::countDown).run();
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "session-lifecycle-test-client");
            handler.start();
        }

        private Message exchange(Message request) throws IOException {
            JsonUtil.writeMessage(output, request);
            return JsonUtil.readMessage(input);
        }

        private void assertStopped() throws InterruptedException {
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "Disconnect cleanup must run.");
            handler.join(5000);
            assertFalse(handler.isAlive(), "Client handler must stop after socket closure.");
            assertNull(failure.get(), "Client handler must not fail unexpectedly.");
        }

        @Override
        public void close() throws Exception {
            try {
                client.close();
            } finally {
                try {
                    serverSide.close();
                } finally {
                    assertStopped();
                }
            }
        }
    }
}
