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
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatProtocolTest {
    // Test fixture stands in for a future successful backend authentication result.
    private static final SessionManager.VerifiedIdentity VERIFIED_USER =
            new SessionManager.VerifiedIdentity("verified-user-1", "CITIZEN");

    @Test
    void verifiedHeartbeatsAcknowledgeAndUseServerTimeRatherThanClientClaims() throws Exception {
        var time = new AtomicLong();
        var sessions = new SessionManager(time::get);
        try (Connection connection = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, connection.serverSide);
            assertEquals(0, sessions.getLastHeartbeatNanos(connection.serverSide).orElseThrow());

            time.set(TimeUnit.SECONDS.toNanos(10));
            var claims = JsonNodeFactory.instance.objectNode()
                    .put("timestamp", Long.MAX_VALUE)
                    .put("lastHeartbeatNanos", Long.MAX_VALUE)
                    .put("userId", "administrator")
                    .put("role", "ADMIN")
                    .put("token", "unverified-payload-token");
            Message response = connection.exchange(new Message(MessageType.HEARTBEAT,
                    "heartbeat-1", "unverified-envelope-token", claims));

            assertAcknowledged(response, "heartbeat-1");
            assertEquals(TimeUnit.SECONDS.toNanos(10),
                    sessions.getLastHeartbeatNanos(connection.serverSide).orElseThrow());
            assertEquals(VERIFIED_USER,
                    sessions.findOnlineSessions(VERIFIED_USER.userId()).getFirst().identity());
            assertFalse(sessions.isOnline("administrator"));

            time.set(TimeUnit.SECONDS.toNanos(20));
            assertAcknowledged(connection.exchange(heartbeat("heartbeat-2")), "heartbeat-2");
            assertEquals(TimeUnit.SECONDS.toNanos(20),
                    sessions.getLastHeartbeatNanos(connection.serverSide).orElseThrow());
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    @Test
    void unauthenticatedHeartbeatDoesNotCreateASessionOrBecomeAuthenticated() throws Exception {
        var time = new AtomicLong();
        var sessions = new SessionManager(time::get);
        try (Connection connection = new Connection(sessions)) {
            var claims = JsonNodeFactory.instance.objectNode()
                    .put("userId", VERIFIED_USER.userId())
                    .put("username", "administrator")
                    .put("role", "ADMIN")
                    .put("token", "claimed-token")
                    .put("timestamp", Long.MAX_VALUE);
            for (MessageType type : new MessageType[]{
                    MessageType.HEARTBEAT, MessageType.LOGIN, MessageType.LOGIN_OK}) {
                Message response = connection.exchange(new Message(type, "claim-" + type,
                        "unverified-token", claims));
                assertNotImplemented(response, "claim-" + type);
                assertEquals(0, sessions.getAuthenticatedSessionCount());
                assertTrue(sessions.getLastHeartbeatNanos(connection.serverSide).isEmpty());
            }

            time.set(TimeUnit.SECONDS.toNanos(100));
            assertEquals(0, sessions.expireSessions(Duration.ofSeconds(35)),
                    "Heartbeat timeout monitoring applies only to authenticated sessions.");
            assertNotImplemented(connection.exchange(heartbeat("still-unverified")), "still-unverified");
        }
    }

    @Test
    void claimedUserIdCannotRefreshAnotherAuthenticatedConnection() throws Exception {
        var time = new AtomicLong();
        var sessions = new SessionManager(time::get);
        try (Connection authenticated = new Connection(sessions); Connection anonymous = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, authenticated.serverSide);
            time.set(TimeUnit.SECONDS.toNanos(10));
            Message response = anonymous.exchange(new Message(MessageType.HEARTBEAT,
                    "spoofed-heartbeat", "unverified-token", JsonNodeFactory.instance.objectNode()
                    .put("userId", VERIFIED_USER.userId()).put("role", VERIFIED_USER.role())));

            assertNotImplemented(response, "spoofed-heartbeat");
            assertEquals(0, sessions.getLastHeartbeatNanos(authenticated.serverSide).orElseThrow());
            assertTrue(sessions.getLastHeartbeatNanos(anonymous.serverSide).isEmpty());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void expiryClosesPendingFrameReadAndPreventsFurtherEventDelivery() throws Exception {
        var time = new AtomicLong();
        var sessions = new SessionManager(time::get);
        var router = new EventRouter(sessions);
        try (Connection connection = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, connection.serverSide);
            time.set(TimeUnit.SECONDS.toNanos(10));
            assertAcknowledged(connection.exchange(heartbeat("before-timeout")), "before-timeout");
            connection.output.writeInt(100);
            connection.output.writeByte('{');
            connection.output.flush();

            time.set(TimeUnit.SECONDS.toNanos(45));
            assertEquals(1, sessions.expireSessions(Duration.ofSeconds(35)));
            connection.assertStopped();

            assertTrue(connection.serverSide.isClosed());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.getLastHeartbeatNanos(connection.serverSide).isEmpty());
            assertFalse(sessions.recordHeartbeat(connection.serverSide));
            assertTrue(router.sendToUser(VERIFIED_USER.userId(), new Message(MessageType.NOTIFICATION,
                    JsonNodeFactory.instance.objectNode().put("text", "must not be delivered"))).offline());
            try {
                assertEquals(-1, connection.input.read(), "The timed-out connection must be closed.");
            } catch (SocketException expectedReset) {
                // Closing a socket with a partial incoming frame may reset the peer.
            }
        }
    }

    @Test
    void expiringOneConnectionPreservesHealthyConnectionForTheSameUser() throws Exception {
        var time = new AtomicLong();
        var sessions = new SessionManager(time::get);
        try (Connection idle = new Connection(sessions); Connection healthy = new Connection(sessions)) {
            sessions.registerVerifiedSession(VERIFIED_USER, idle.serverSide);
            sessions.registerVerifiedSession(VERIFIED_USER, healthy.serverSide);
            time.set(TimeUnit.SECONDS.toNanos(10));
            assertAcknowledged(healthy.exchange(heartbeat("healthy-1")), "healthy-1");

            time.set(TimeUnit.SECONDS.toNanos(35));
            assertEquals(1, sessions.expireSessions(Duration.ofSeconds(35)));
            idle.assertStopped();

            assertTrue(idle.serverSide.isClosed());
            assertFalse(healthy.serverSide.isClosed());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertTrue(sessions.isOnline(VERIFIED_USER.userId()));
            assertSame(healthy.serverSide,
                    sessions.findOnlineSessions(VERIFIED_USER.userId()).getFirst().connection());
            assertAcknowledged(healthy.exchange(heartbeat("healthy-2")), "healthy-2");
            assertEquals(TimeUnit.SECONDS.toNanos(35),
                    sessions.getLastHeartbeatNanos(healthy.serverSide).orElseThrow());
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    private static Message heartbeat(String correlationId) {
        return new Message(MessageType.HEARTBEAT, correlationId, null, JsonNodeFactory.instance.objectNode());
    }

    private static void assertAcknowledged(Message response, String correlationId) {
        assertNotNull(response);
        assertEquals(MessageType.ACK, response.type());
        assertEquals(correlationId, response.correlationId());
        assertNull(response.token());
        assertEquals("HEARTBEAT", response.payload().path("acknowledgedType").asText());
    }

    private static void assertNotImplemented(Message response, String correlationId) {
        assertNotNull(response);
        assertEquals(MessageType.ERROR, response.type());
        assertEquals(correlationId, response.correlationId());
        assertNull(response.token());
        assertEquals("NOT_IMPLEMENTED", response.payload().path("code").asText());
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
            }, "heartbeat-protocol-test-client");
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
