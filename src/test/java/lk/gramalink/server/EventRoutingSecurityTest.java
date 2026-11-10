package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EventRoutingSecurityTest {
    // Fixtures stand in for backend-verified identities in tests only.
    private static final SessionManager.VerifiedIdentity RECIPIENT =
            new SessionManager.VerifiedIdentity("recipient-user", "CITIZEN");
    private static final SessionManager.VerifiedIdentity SENDER =
            new SessionManager.VerifiedIdentity("sender-user", "CITIZEN");

    @Test
    void concurrentPushesAndHandlerRepliesShareOneWriterAndPreserveEveryFrame() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (Connection connection = new Connection(sessions)) {
            sessions.registerVerifiedSession(RECIPIENT, connection.serverSide);
            int pushWorkers = 6;
            int pushesPerWorker = 25;
            int clientRequests = 30;
            MessageType[] eventTypes = {MessageType.NEW_REQUEST, MessageType.REQUEST_UPDATED,
                    MessageType.CHAT_MESSAGE, MessageType.NOTIFICATION};
            var expectedTypes = new HashMap<String, MessageType>();
            for (int worker = 0; worker < pushWorkers; worker++) {
                for (int number = 0; number < pushesPerWorker; number++) {
                    expectedTypes.put("event-" + worker + "-" + number,
                            eventTypes[worker % eventTypes.length]);
                }
            }
            for (int number = 0; number < clientRequests; number++) {
                expectedTypes.put("client-" + number, MessageType.ACK);
            }

            var workers = Executors.newFixedThreadPool(pushWorkers + 2);
            var ready = new CountDownLatch(pushWorkers + 2);
            var start = new CountDownLatch(1);
            try {
                Future<?> reader = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var received = new HashSet<String>();
                    for (int count = 0; count < expectedTypes.size(); count++) {
                        Message message = JsonUtil.readMessage(connection.input);
                        assertNotNull(message, "Socket must remain open until all frames arrive.");
                        assertEquals(expectedTypes.get(message.correlationId()), message.type());
                        assertTrue(received.add(message.correlationId()), "Frames must not be duplicated.");
                        if (message.type() == MessageType.ACK) {
                            assertEquals("HEARTBEAT", message.payload().path("acknowledgedType").asText());
                        } else {
                            assertEquals(message.correlationId(), message.payload().path("eventId").asText());
                            assertEquals("x".repeat(1024), message.payload().path("text").asText());
                        }
                    }
                    assertEquals(expectedTypes.keySet(), received);
                    return null;
                });
                var senders = new ArrayList<Future<?>>();
                senders.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int number = 0; number < clientRequests; number++) {
                        JsonUtil.writeMessage(connection.output, new Message(MessageType.HEARTBEAT,
                                "client-" + number, null, JsonNodeFactory.instance.objectNode()));
                    }
                    return null;
                }));
                for (int worker = 0; worker < pushWorkers; worker++) {
                    int index = worker;
                    senders.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int number = 0; number < pushesPerWorker; number++) {
                            String id = "event-" + index + "-" + number;
                            var payload = JsonNodeFactory.instance.objectNode()
                                    .put("eventId", id).put("text", "x".repeat(1024));
                            var result = router.sendToUser(RECIPIENT.userId(),
                                    new Message(eventTypes[index % eventTypes.length], id, null, payload));
                            assertEquals(1, result.deliveredConnections());
                            assertEquals(0, result.failedConnections());
                            assertFalse(result.offline());
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> sender : senders) {
                    sender.get(10, TimeUnit.SECONDS);
                }
                reader.get(10, TimeUnit.SECONDS);
                assertEquals(1, connection.serverSide.outputLookups.get(),
                        "Handler responses and router events must reuse the same socket output wrapper.");
            } finally {
                start.countDown();
                // Closing the socket releases any blocked test reader even if an assertion fails.
                connection.client.close();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    @Test
    void staleSessionCannotReceiveEventsAfterSameSocketChangesIdentity() throws Exception {
        var sessions = new SessionManager();
        var router = new EventRouter(sessions);
        try (Connection connection = new Connection(sessions)) {
            var previous = sessions.registerVerifiedSession(RECIPIENT, connection.serverSide);
            Message event = new Message(MessageType.NEW_REQUEST,
                    JsonNodeFactory.instance.objectNode().put("requestId", "private-request"));
            assertEquals(1, router.sendToUser(RECIPIENT.userId(), event).deliveredConnections());
            assertEquals(event, JsonUtil.readMessage(connection.input));

            sessions.removeSession(connection.serverSide);
            sessions.registerVerifiedSession(SENDER, connection.serverSide);
            assertFalse(sessions.sendToSession(previous, event),
                    "An old routing snapshot must not send private data to a new socket identity.");
            assertTrue(router.sendToUser(RECIPIENT.userId(), event).offline());

            Message next = new Message(MessageType.NOTIFICATION,
                    JsonNodeFactory.instance.objectNode().put("text", "New identity's notification"));
            assertEquals(1, router.sendToUser(SENDER.userId(), next).deliveredConnections());
            assertEquals(next, JsonUtil.readMessage(connection.input));
            assertEquals(1, connection.serverSide.outputLookups.get(),
                    "Re-registering an open socket must preserve its sole writer.");
        }
    }

    @Test
    void clientClaimedRecipientsAndServerEventTypesCannotTriggerPrivatePushes() throws Exception {
        var sessions = new SessionManager();
        try (Connection sender = new Connection(sessions); Connection recipient = new Connection(sessions)) {
            sessions.registerVerifiedSession(SENDER, sender.serverSide);
            sessions.registerVerifiedSession(RECIPIENT, recipient.serverSide);
            MessageType[] attempts = {MessageType.NEW_REQUEST, MessageType.REQUEST_UPDATED,
                    MessageType.CHAT_MESSAGE, MessageType.NOTIFICATION, MessageType.LOGIN_OK, MessageType.LOGIN};
            for (MessageType type : attempts) {
                var forgedPayload = JsonNodeFactory.instance.objectNode()
                        .put("recipientId", RECIPIENT.userId())
                        .put("userId", "administrator")
                        .put("role", "ADMIN")
                        .put("token", "unverified-payload-token")
                        .put("privateRequestData", "must-not-be-forwarded");
                forgedPayload.putArray("recipientIds").add(RECIPIENT.userId());
                Message response = sender.exchange(new Message(type, "attempt-" + type,
                        "unverified-envelope-token", forgedPayload));

                assertNotNull(response);
                assertEquals(MessageType.ERROR, response.type());
                assertEquals("NOT_IMPLEMENTED", response.payload().path("code").asText());
                assertEquals("attempt-" + type, response.correlationId());
                assertNull(response.token());
            }
            recipient.client.setSoTimeout(250);
            assertThrows(SocketTimeoutException.class, () -> JsonUtil.readMessage(recipient.input),
                    "No client-originated event may push data to another user's connection.");
            assertEquals(2, sessions.getAuthenticatedSessionCount());
            assertFalse(sessions.isOnline("administrator"));
            assertEquals(SENDER, sessions.findOnlineSessions(SENDER.userId()).getFirst().identity());
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    private static final class Connection implements AutoCloseable {
        private final Socket client;
        private final CountingSocket serverSide;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread handler;

        private Connection(SessionManager sessions) throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                serverSide = new CountingSocket(listener.accept());
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
            }, "event-routing-test-handler");
            handler.start();
        }

        private Message exchange(Message message) throws IOException {
            JsonUtil.writeMessage(output, message);
            return JsonUtil.readMessage(input);
        }

        @Override
        public void close() throws Exception {
            try {
                client.close();
            } finally {
                try {
                    serverSide.close();
                } finally {
                    assertTrue(disconnected.await(5, TimeUnit.SECONDS), "Handler cleanup must run.");
                    handler.join(5000);
                    assertFalse(handler.isAlive());
                    assertNull(failure.get(), "Handler must not fail unexpectedly.");
                }
            }
        }
    }

    /** Observes stream creation while all network I/O still uses a real loopback socket. */
    private static final class CountingSocket extends Socket {
        private final Socket delegate;
        private final AtomicInteger outputLookups = new AtomicInteger();

        private CountingSocket(Socket delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            outputLookups.incrementAndGet();
            return delegate.getOutputStream();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public boolean isInputShutdown() {
            return delegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegate.isOutputShutdown();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
