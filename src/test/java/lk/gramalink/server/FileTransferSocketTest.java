package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.TransportCodec;
import lk.gramalink.shared.TransportFrame;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileTransferSocketTest {
    // Represents successful backend verification in unit tests only.
    private static final SessionManager.VerifiedIdentity USER =
            new SessionManager.VerifiedIdentity("file-user", "CITIZEN");

    @Test
    void concurrentBinaryJsonAndHeartbeatRepliesReuseOneWriterWithoutInterleaving() throws Exception {
        var sessions = new SessionManager();
        try (Connection connection = new Connection(sessions)) {
            var session = sessions.registerVerifiedSession(USER, connection.serverSide);
            var transferId = UUID.randomUUID();
            int jsonEvents = 45;
            int binaryChunks = 40;
            int heartbeats = 25;
            var workers = Executors.newFixedThreadPool(4);
            var ready = new CountDownLatch(4);
            var start = new CountDownLatch(1);
            try {
                Future<?> reader = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var jsonIds = new HashSet<String>();
                    int nextSequence = 0;
                    for (int count = 0; count < jsonEvents + binaryChunks + heartbeats; count++) {
                        TransportFrame frame = TransportCodec.readFrame(connection.input);
                        assertNotNull(frame);
                        if (frame instanceof FileChunk chunk) {
                            assertEquals(transferId, chunk.transferId());
                            assertEquals(nextSequence, chunk.sequence());
                            assertEquals(nextSequence == binaryChunks - 1, chunk.last());
                            assertArrayEquals(chunkBytes(nextSequence), chunk.data());
                            nextSequence++;
                        } else {
                            Message message = ((TransportFrame.JsonFrame) frame).message();
                            assertTrue(jsonIds.add(message.correlationId()), "JSON frames must not be duplicated.");
                            if (message.correlationId().startsWith("event-")) {
                                assertEquals(MessageType.NOTIFICATION, message.type());
                                assertEquals("x".repeat(1024), message.payload().path("text").asText());
                            } else {
                                assertTrue(message.correlationId().startsWith("heartbeat-"));
                                assertEquals(MessageType.ACK, message.type());
                                assertEquals("HEARTBEAT", message.payload().path("acknowledgedType").asText());
                            }
                        }
                    }
                    assertEquals(binaryChunks, nextSequence);
                    assertEquals(jsonEvents + heartbeats, jsonIds.size());
                    return null;
                });
                Future<?> events = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var router = new EventRouter(sessions);
                    for (int number = 0; number < jsonEvents; number++) {
                        var event = new Message(MessageType.NOTIFICATION, "event-" + number, null,
                                JsonNodeFactory.instance.objectNode().put("text", "x".repeat(1024)));
                        assertEquals(1, router.sendToUser(USER.userId(), event).deliveredConnections());
                    }
                    return null;
                });
                Future<?> chunks = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int number = 0; number < binaryChunks; number++) {
                        assertTrue(sessions.sendChunkToSession(session,
                                new FileChunk(transferId, number, number == binaryChunks - 1, chunkBytes(number))));
                    }
                    return null;
                });
                Future<?> requests = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int number = 0; number < heartbeats; number++) {
                        JsonUtil.writeMessage(connection.output, new Message(MessageType.HEARTBEAT,
                                "heartbeat-" + number, null, JsonNodeFactory.instance.objectNode()));
                    }
                    return null;
                });
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                for (Future<?> sender : List.of(events, chunks, requests)) {
                    sender.get(10, TimeUnit.SECONDS);
                }
                reader.get(10, TimeUnit.SECONDS);
                assertEquals(1, connection.serverSide.outputLookups.get(),
                        "Binary chunks, routed events and replies must share the sole socket writer.");
            } finally {
                start.countDown();
                connection.client.close();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    @Test
    void unverifiedClaimsAndUnavailableServicesNeverAcceptActualFileOperations() throws Exception {
        var sessions = new SessionManager();
        try (Connection connection = new Connection(sessions)) {
            UUID transferId = UUID.randomUUID();
            var claims = JsonNodeFactory.instance.objectNode()
                    .put("transferId", transferId.toString()).put("direction", "UPLOAD")
                    .put("fileName", "private.pdf").put("totalBytes", 3)
                    .put("userId", USER.userId()).put("role", "ADMIN").put("token", "unverified");
            for (MessageType type : new MessageType[]{
                    MessageType.FILE_META, MessageType.FILE_CHUNK, MessageType.LOGIN, MessageType.LOGIN_OK}) {
                assertError(connection.exchange(new Message(type, "claim-" + type, "unverified", claims)),
                        "NOT_IMPLEMENTED");
                assertEquals(0, sessions.getAuthenticatedSessionCount());
            }
            TransportCodec.writeChunk(connection.output, new FileChunk(transferId, 0, true, new byte[]{1, 2, 3}));
            assertError(JsonUtil.readMessage(connection.input), "AUTHENTICATION_REQUIRED");
            assertEquals(0, sessions.getAuthenticatedSessionCount());

            sessions.registerVerifiedSession(USER, connection.serverSide);
            assertError(connection.exchange(new Message(MessageType.FILE_META, claims)), "NOT_IMPLEMENTED");
            TransportCodec.writeChunk(connection.output, new FileChunk(transferId, 0, true, new byte[]{1, 2, 3}));
            assertError(JsonUtil.readMessage(connection.input), "UNKNOWN_TRANSFER");
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertEquals(MessageType.ACK, connection.exchange(new Message(MessageType.HEARTBEAT,
                    JsonNodeFactory.instance.objectNode())).type());
        }
    }

    @Test
    void truncatedBinaryChunkClosesOnlyItsConnectionAndCleansTheSession() throws Exception {
        var sessions = new SessionManager();
        try (Connection broken = new Connection(sessions); Connection healthy = new Connection(sessions)) {
            sessions.registerVerifiedSession(USER, broken.serverSide);
            sessions.registerVerifiedSession(USER, healthy.serverSide);
            UUID transferId = UUID.randomUUID();
            broken.output.writeInt(TransportCodec.BINARY_MARKER);
            broken.output.writeInt(31 + 32);
            broken.output.writeByte(1);
            broken.output.writeByte(1);
            broken.output.writeLong(transferId.getMostSignificantBits());
            broken.output.writeLong(transferId.getLeastSignificantBits());
            broken.output.writeLong(0);
            broken.output.writeByte(1);
            broken.output.writeInt(32);
            broken.output.write(new byte[]{1, 2, 3});
            broken.output.flush();
            broken.client.shutdownOutput();
            broken.assertStopped();

            assertTrue(broken.serverSide.isClosed());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertSame(healthy.serverSide, sessions.findOnlineSessions(USER.userId()).getFirst().connection());
            Message reply = healthy.exchange(new Message(MessageType.HEARTBEAT,
                    JsonNodeFactory.instance.objectNode()));
            assertEquals(MessageType.ACK, reply.type());
            assertPeerClosed(broken.input);
        }
        assertEquals(0, sessions.getAuthenticatedSessionCount());
    }

    @Test
    void oversizedBinaryBodyIsRejectedWithoutWaitingForOrAllocatingItsContents() throws Exception {
        var sessions = new SessionManager();
        try (Connection connection = new Connection(sessions)) {
            sessions.registerVerifiedSession(USER, connection.serverSide);
            connection.output.writeInt(TransportCodec.BINARY_MARKER);
            connection.output.writeInt(Integer.MAX_VALUE);
            connection.output.flush();
            connection.assertStopped();

            assertTrue(connection.serverSide.isClosed());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertPeerClosed(connection.input);
        }
    }

    @Test
    void testAuthorizedTransferNegotiatesThenReceivesBinaryChunksAlongsideHeartbeats() throws Exception {
        var sessions = new SessionManager();
        UUID transferId = UUID.randomUUID();
        var received = new CopyOnWriteArrayList<FileChunk>();
        FileTransferService service = new FileTransferService() {
            @Override
            public Grant authorize(SessionManager.Session session, Message metadata) {
                assertEquals(USER, session.identity());
                assertEquals(MessageType.FILE_META, metadata.type());
                return new Grant(transferId, Direction.UPLOAD, 5);
            }

            @Override
            public void receiveChunk(SessionManager.Session session, Grant grant, FileChunk chunk) {
                received.add(chunk); // Small in-memory test fixture; no file storage.
            }
        };
        try (Connection connection = new Connection(sessions, service)) {
            sessions.registerVerifiedSession(USER, connection.serverSide);
            Message ready = connection.exchange(new Message(MessageType.FILE_META, "meta", null,
                    JsonNodeFactory.instance.objectNode().put("direction", "UPLOAD").put("totalBytes", 5)));
            assertEquals(MessageType.FILE_META, ready.type());
            assertEquals("meta", ready.correlationId());
            assertEquals("READY", ready.payload().path("status").asText());
            assertEquals(transferId.toString(), ready.payload().path("transferId").asText());

            TransportCodec.writeChunk(connection.output, new FileChunk(transferId, 0, false, new byte[]{1, 2}));
            Message first = JsonUtil.readMessage(connection.input);
            assertEquals(MessageType.ACK, first.type());
            assertEquals("TRANSPORT_RECEIVED", first.payload().path("stage").asText());
            assertEquals(2, first.payload().path("receivedBytes").asLong());
            assertFalse(first.payload().path("complete").asBoolean());
            assertEquals(MessageType.ACK, connection.exchange(new Message(MessageType.HEARTBEAT,
                    JsonNodeFactory.instance.objectNode())).type());

            TransportCodec.writeChunk(connection.output, new FileChunk(transferId, 1, true, new byte[]{3, 4, 5}));
            Message last = JsonUtil.readMessage(connection.input);
            assertEquals(MessageType.ACK, last.type());
            assertEquals(5, last.payload().path("receivedBytes").asLong());
            assertTrue(last.payload().path("complete").asBoolean());
            assertEquals(2, received.size());
            assertArrayEquals(new byte[]{1, 2}, received.getFirst().data());
            assertArrayEquals(new byte[]{3, 4, 5}, received.getLast().data());
        }
    }

    private static byte[] chunkBytes(int sequence) {
        byte[] bytes = new byte[1536];
        Arrays.fill(bytes, (byte) sequence);
        return bytes;
    }

    private static void assertError(Message message, String code) {
        assertNotNull(message);
        assertEquals(MessageType.ERROR, message.type());
        assertEquals(code, message.payload().path("code").asText());
        assertNull(message.token());
    }

    private static void assertPeerClosed(DataInputStream input) throws IOException {
        try {
            assertEquals(-1, input.read());
        } catch (SocketException expectedReset) {
            // Closing a broken frame may reset a TCP connection with unread data.
        }
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
            this(sessions, FileTransferService.unavailable());
        }

        private Connection(SessionManager sessions, FileTransferService service) throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                serverSide = new CountingSocket(listener.accept());
            }
            client.setSoTimeout(5000);
            input = new DataInputStream(client.getInputStream());
            output = new DataOutputStream(client.getOutputStream());
            handler = new Thread(() -> {
                try {
                    new ClientHandler(serverSide, sessions, service, disconnected::countDown).run();
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "file-transfer-socket-test-handler");
            handler.start();
        }

        private Message exchange(Message message) throws IOException {
            JsonUtil.writeMessage(output, message);
            return JsonUtil.readMessage(input);
        }

        private void assertStopped() throws InterruptedException {
            assertTrue(disconnected.await(5, TimeUnit.SECONDS), "Connection cleanup must run.");
            handler.join(5000);
            assertFalse(handler.isAlive(), "Handler must stop after connection interruption.");
            assertNull(failure.get(), "Handler must not fail unexpectedly.");
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
