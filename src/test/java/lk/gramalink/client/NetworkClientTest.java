package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.server.NetworkServer;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class NetworkClientTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private static Message message(MessageType type, String id) {
        return new Message(type, id, null, JsonNodeFactory.instance.objectNode().put("text", "தமிழ்"));
    }
    private static final class Events implements NetworkClient.Listener {
        final BlockingQueue<NetworkClient.Status> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<FileChunk> chunks = new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        public void onStatus(NetworkClient.Status status) { statuses.add(status); }
        public void onMessage(Message message) { messages.add(message); }
        public void onChunk(FileChunk chunk) { chunks.add(chunk); }
        public void onError(Throwable error) { errors.add(error); }
    }
    private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T item = queue.poll(3, TimeUnit.SECONDS);
        assertNotNull(item, "Expected callback within three seconds");
        return item;
    }
    private static final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final Events events = new Events();
        final NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort(), events, Runnable::run);
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        Peer() throws IOException {
            listener.setSoTimeout(3000);
            client.connect();
            socket = listener.accept();
            socket.setSoTimeout(3000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }
        public void close() throws IOException { client.close(); socket.close(); listener.close(); }
    }

    @Test void connectsSendsExactUtf8LengthAndDisconnectsIdempotently() throws Exception {
        try (Peer p = new Peer()) {
            assertTrue(p.client.isConnected());
            assertEquals(NetworkClient.Status.CONNECTED, take(p.events.statuses));
            Message sent = message(MessageType.HEARTBEAT, "one");
            p.client.send(sent);
            int length = p.input.readInt();
            byte[] body = p.input.readNBytes(length);
            assertEquals(JsonUtil.toJson(sent).getBytes(java.nio.charset.StandardCharsets.UTF_8).length, length);
            assertEquals(sent, JsonUtil.fromJson(new String(body, java.nio.charset.StandardCharsets.UTF_8)));
            p.client.disconnect();
            p.client.disconnect();
            assertFalse(p.client.isConnected());
            assertEquals(-1, p.input.read());
            assertEquals(NetworkClient.Status.DISCONNECTED, take(p.events.statuses));
            assertTrue(p.events.errors.isEmpty());
            assertThrows(IOException.class, () -> p.client.send(sent));
        }
    }

    @Test void receivesFragmentedJsonBinaryAndUnsolicitedEvents() throws Exception {
        try (Peer p = new Peer()) {
            Message event = message(MessageType.NOTIFICATION, null);
            var bytes = new ByteArrayOutputStream();
            JsonUtil.writeMessage(new DataOutputStream(bytes), event);
            for (byte value : bytes.toByteArray()) { p.output.write(value); p.output.flush(); }
            FileChunk chunk = new FileChunk(UUID.randomUUID(), 0, true, new byte[]{1, 2, 3});
            TransportCodec.writeChunk(p.output, chunk);
            JsonUtil.writeMessage(p.output, message(MessageType.NEW_REQUEST, "event"));
            assertEquals(event, take(p.events.messages));
            assertEquals(chunk, take(p.events.chunks));
            assertEquals("event", take(p.events.messages).correlationId());
        }
    }

    @Test void correlatesOutOfOrderRepliesAndGeneratesIds() throws Exception {
        try (Peer p = new Peer()) {
            var first = p.client.request(message(MessageType.LOGIN, "first"), WAIT);
            var second = p.client.request(message(MessageType.HEARTBEAT, null), WAIT);
            Message a = JsonUtil.readMessage(p.input), b = JsonUtil.readMessage(p.input);
            assertNotNull(b.correlationId());
            JsonUtil.writeMessage(p.output, message(MessageType.ERROR, b.correlationId()));
            JsonUtil.writeMessage(p.output, message(MessageType.ACK, a.correlationId()));
            assertEquals(MessageType.ERROR, second.get(3, TimeUnit.SECONDS).type());
            assertEquals("first", first.get(3, TimeUnit.SECONDS).correlationId());
            assertNotNull(take(p.events.messages));
            assertNotNull(take(p.events.messages));
        }
    }

    @Test void pendingRequestsTimeoutCancelAndRejectDuplicateIds() throws Exception {
        try (Peer p = new Peer()) {
            var waiting = p.client.request(message(MessageType.LOGIN, "duplicate"), WAIT);
            assertThrows(IllegalArgumentException.class,
                    () -> p.client.request(message(MessageType.LOGIN, "duplicate"), WAIT));
            assertTrue(waiting.cancel(false));
            var expired = p.client.request(message(MessageType.LOGIN, "duplicate"), Duration.ofMillis(30));
            assertInstanceOf(TimeoutException.class,
                    assertThrows(ExecutionException.class, () -> expired.get(3, TimeUnit.SECONDS)).getCause());
        }
    }

    @Test void concurrentJsonAndBinaryWritesRemainWholeFrames() throws Exception {
        try (Peer p = new Peer(); ExecutorService writers = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writes = new ArrayList<>();
            UUID transfer = UUID.randomUUID();
            for (int i = 0; i < 80; i++) {
                int index = i;
                writes.add(writers.submit(() -> {
                    if (index % 2 == 0) p.client.send(message(MessageType.HEARTBEAT, "id-" + index));
                    else p.client.sendChunk(new FileChunk(transfer, index, false, new byte[1024]));
                    return null;
                }));
            }
            Set<String> ids = new HashSet<>();
            Set<Long> sequences = new HashSet<>();
            for (int i = 0; i < 80; i++) {
                TransportFrame frame = TransportCodec.readFrame(p.input);
                if (frame instanceof FileChunk chunk) {
                    assertEquals(transfer, chunk.transferId());
                    assertEquals(1024, chunk.dataLength());
                    assertTrue(sequences.add(chunk.sequence()));
                } else assertTrue(ids.add(((TransportFrame.JsonFrame) frame).message().correlationId()));
            }
            for (Future<?> write : writes) write.get(3, TimeUnit.SECONDS);
            assertEquals(40, ids.size());
            assertEquals(40, sequences.size());
        }
    }

    @Test void malformedFrameReportsErrorAndFailsPendingRequest() throws Exception {
        try (Peer p = new Peer()) {
            var reply = p.client.request(message(MessageType.LOGIN, "pending"), WAIT);
            p.output.writeInt(0);
            p.output.flush();
            assertInstanceOf(IOException.class, take(p.events.errors));
            assertInstanceOf(IOException.class,
                    assertThrows(ExecutionException.class, () -> reply.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(NetworkClient.Status.CONNECTED, take(p.events.statuses));
            assertEquals(NetworkClient.Status.DISCONNECTED, take(p.events.statuses));
            assertFalse(p.client.isConnected());
        }
    }

    @Test void remoteEofAndLocalCloseFailOutstandingRequests() throws Exception {
        for (boolean remote : List.of(true, false)) {
            try (Peer p = new Peer()) {
                var reply = p.client.request(message(MessageType.LOGIN, "pending"), WAIT);
                if (remote) p.socket.close(); else p.client.close();
                assertInstanceOf(IOException.class,
                        assertThrows(ExecutionException.class, () -> reply.get(3, TimeUnit.SECONDS)).getCause());
                assertEquals(NetworkClient.Status.CONNECTED, take(p.events.statuses));
                assertEquals(NetworkClient.Status.DISCONNECTED, take(p.events.statuses));
            }
        }
    }

    @Test void reconnectsWithoutOldReceiverClosingNewConnection() throws Exception {
        try (Peer p = new Peer()) {
            p.client.disconnect();
            p.client.connect();
            try (Socket next = p.listener.accept()) {
                next.setSoTimeout(3000);
                p.client.send(message(MessageType.HEARTBEAT, "new"));
                assertEquals("new", JsonUtil.readMessage(new DataInputStream(next.getInputStream())).correlationId());
            }
        }
    }

    @Test void truncatedFrameReportsEofError() throws Exception {
        try (Peer p = new Peer()) {
            p.output.writeInt(50);
            p.output.writeByte('{');
            p.output.flush();
            p.socket.shutdownOutput();
            assertInstanceOf(EOFException.class, take(p.events.errors));
            assertFalse(p.client.isConnected());
        }
    }

    @Test void callbacksUseSuppliedExecutorAndListenerFailureDoesNotStopReceiver() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        Events events = new Events();
        try (ServerSocket listener = new ServerSocket(0);
             NetworkClient client = new NetworkClient("127.0.0.1", listener.getLocalPort(),
                     new NetworkClient.Listener() {
                         public void onStatus(NetworkClient.Status status) { events.onStatus(status); }
                         public void onMessage(Message message) {
                             events.onMessage(message);
                             throw new IllegalStateException("UI listener failure");
                         }
                     }, callbacks::add)) {
            client.connect();
            try (Socket socket = listener.accept()) {
                assertTrue(events.statuses.isEmpty());
                take(callbacks).run();
                assertEquals(NetworkClient.Status.CONNECTED, take(events.statuses));
                var output = new DataOutputStream(socket.getOutputStream());
                JsonUtil.writeMessage(output, message(MessageType.NOTIFICATION, "one"));
                JsonUtil.writeMessage(output, message(MessageType.NOTIFICATION, "two"));
                take(callbacks).run();
                take(callbacks).run();
                assertEquals("one", take(events.messages).correlationId());
                assertEquals("two", take(events.messages).correlationId());
                assertTrue(client.isConnected());
            }
        }
    }

    @Test void refusedConnectionReportsErrorAndValidatesConfiguration() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        Events events = new Events();
        try (NetworkClient client = new NetworkClient("127.0.0.1", port, events, Runnable::run)) {
            assertThrows(IOException.class, client::connect);
            assertInstanceOf(IOException.class, take(events.errors));
            assertFalse(client.isConnected());
            assertThrows(IllegalArgumentException.class, () -> client.connect(Duration.ZERO));
        }
        assertThrows(IllegalArgumentException.class, () -> new NetworkClient("", 5000));
        assertThrows(IllegalArgumentException.class, () -> new NetworkClient("127.0.0.1", 0));
        assertEquals("127.0.0.1", NetworkClient.DEFAULT_HOST);
        assertEquals(5000, NetworkClient.DEFAULT_PORT);
    }

    @Test void existingServerReturnsNotImplementedForUnauthenticatedHeartbeat() throws Exception {
        int port;
        try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
        try (NetworkServer server = new NetworkServer(port)) {
            ExecutorService listener = Executors.newSingleThreadExecutor();
            Future<?> serving = listener.submit(() -> { server.start(); return null; });
            try (NetworkClient client = new NetworkClient("127.0.0.1", port)) {
                client.connect();
                Message reply = client.request(message(MessageType.HEARTBEAT, "u06-heartbeat"), WAIT)
                        .get(3, TimeUnit.SECONDS);
                assertEquals(MessageType.ERROR, reply.type());
                assertEquals("u06-heartbeat", reply.correlationId());
                assertEquals("NOT_IMPLEMENTED", reply.payload().path("code").asText());
                assertNull(reply.token());
            } finally {
                server.close();
                listener.shutdown();
                serving.get(3, TimeUnit.SECONDS);
                assertTrue(listener.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }
}
