package lk.gramalink.shared;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilTest {
    private static ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static Message message() {
        return new Message(MessageType.CHAT_MESSAGE, "request-17", "test-token",
                payload().put("text", "ආයුබෝවන් — வணக்கம் 👋").put("count", 3));
    }

    private static byte[] frame(byte[] json) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(json.length);
        output.write(json);
        return bytes.toByteArray();
    }

    private static DataInputStream input(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    @Test
    void jsonRoundTripPreservesUnicodeOptionalFieldsAndObjectPayload() throws IOException {
        Message original = message();
        assertEquals(original, JsonUtil.fromJson(JsonUtil.toJson(original)));
    }

    @Test
    void optionalFieldsMayBeAbsent() throws IOException {
        Message decoded = JsonUtil.fromJson("{\"type\":\"HEARTBEAT\",\"payload\":{}}");
        assertEquals(MessageType.HEARTBEAT, decoded.type());
        assertNull(decoded.correlationId());
        assertNull(decoded.token());
        assertEquals(payload(), decoded.payload());
        assertEquals(decoded, JsonUtil.fromJson(JsonUtil.toJson(decoded)));
    }

    @Test
    void everyAgreedMessageTypeCanBeSerialized() throws IOException {
        for (MessageType type : MessageType.values()) {
            Message original = new Message(type, payload());
            assertEquals(original, JsonUtil.fromJson(JsonUtil.toJson(original)));
        }
    }

    @Test
    void framingUsesFourByteBigEndianUtf8ByteLength() throws IOException {
        Message original = message();
        byte[] json = JsonUtil.toJson(original).getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        JsonUtil.writeMessage(new DataOutputStream(bytes), original);
        byte[] encoded = bytes.toByteArray();

        assertEquals(json.length + Integer.BYTES, encoded.length);
        assertEquals(json.length, ByteBuffer.wrap(encoded).getInt());
        assertArrayEquals(json, java.util.Arrays.copyOfRange(encoded, 4, encoded.length));
        assertEquals(original, JsonUtil.readMessage(input(encoded)));
    }

    @Test
    void readerHandlesAFrameDeliveredOneByteAtATime() throws IOException {
        byte[] encoded = frame(JsonUtil.toJson(message()).getBytes(StandardCharsets.UTF_8));
        InputStream fragmented = new ByteArrayInputStream(encoded) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        assertEquals(message(), JsonUtil.readMessage(new DataInputStream(fragmented)));
    }

    @Test
    void multipleConsecutiveFramesRemainSeparate() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        Message second = new Message(MessageType.LOGIN, payload().put("username", "samhi"));
        JsonUtil.writeMessage(output, message());
        JsonUtil.writeMessage(output, second);
        var input = input(bytes.toByteArray());
        assertEquals(message(), JsonUtil.readMessage(input));
        assertEquals(second, JsonUtil.readMessage(input));
        assertNull(JsonUtil.readMessage(input));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 1048577, Integer.MAX_VALUE})
    void invalidLengthsAreRejectedBeforeReadingPayload(int length) throws IOException {
        var bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(length);
        IOException exception = assertThrows(IOException.class,
                () -> JsonUtil.readMessage(input(bytes.toByteArray())));
        assertFalse(exception instanceof EOFException, "Invalid lengths should fail validation, not wait for a body");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "null", "[]", "{}", "{\"type\":null,\"payload\":{}}",
            "{\"type\":\"UNKNOWN\",\"payload\":{}}", "{\"type\":\"LOGIN\"}",
            "{\"type\":0,\"payload\":{}}",
            "{\"type\":\"LOGIN\",\"payload\":null}", "{\"type\":\"LOGIN\",\"payload\":[]}",
            "{\"type\":\"LOGIN\",\"payload\":\"text\"}",
            "{\"type\":\"LOGIN\",\"payload\":{}} {}",
            "{\"type\":\"LOGIN\",\"type\":\"ACK\",\"payload\":{}}"
    })
    void malformedJsonAndInvalidMessageShapesAreRejected(String json) throws IOException {
        assertThrows(IOException.class, () -> JsonUtil.fromJson(json));
        assertThrows(IOException.class,
                () -> JsonUtil.readMessage(input(frame(json.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void malformedUtf8IsRejected() throws IOException {
        byte[] prefix = "{\"type\":\"LOGIN\",\"payload\":{\"text\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}}".getBytes(StandardCharsets.UTF_8);
        var bytes = new ByteArrayOutputStream();
        bytes.write(prefix);
        bytes.write(new byte[]{(byte) 0xc3, 0x28});
        bytes.write(suffix);
        assertThrows(IOException.class, () -> JsonUtil.readMessage(input(frame(bytes.toByteArray()))));
    }

    @Test
    void cleanEofBeforeAHeaderReturnsNull() throws IOException {
        assertNull(JsonUtil.readMessage(input(new byte[0])));
    }

    @Test
    void truncatedHeadersAreNotTreatedAsCleanEof() {
        for (int length = 1; length < Integer.BYTES; length++) {
            byte[] incomplete = new byte[length];
            assertThrows(EOFException.class, () -> JsonUtil.readMessage(input(incomplete)));
        }
    }

    @Test
    void truncatedBodyIsNotTreatedAsCleanEof() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(10);
        output.write(new byte[]{'{', '}'});
        assertThrows(EOFException.class, () -> JsonUtil.readMessage(input(bytes.toByteArray())));
    }

    @Test
    void payloadExactlyAtLimitIsAccepted() throws IOException {
        ObjectNode payload = payload().put("text", "");
        Message original = new Message(MessageType.CHAT_MESSAGE, payload);
        int overhead = JsonUtil.toJson(original).getBytes(StandardCharsets.UTF_8).length;
        payload.put("text", "x".repeat(JsonUtil.MAX_PAYLOAD_BYTES - overhead));
        var bytes = new ByteArrayOutputStream();
        JsonUtil.writeMessage(new DataOutputStream(bytes), original);
        assertEquals(JsonUtil.MAX_PAYLOAD_BYTES + 4, bytes.size());
        assertEquals(original, JsonUtil.readMessage(input(bytes.toByteArray())));
    }

    @Test
    void oversizedOutgoingMessageWritesNoPartialFrame() {
        Message oversized = new Message(MessageType.CHAT_MESSAGE,
                payload().put("text", "x".repeat(JsonUtil.MAX_PAYLOAD_BYTES)));
        var bytes = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> JsonUtil.writeMessage(new DataOutputStream(bytes), oversized));
        assertEquals(0, bytes.size());
    }

    @Test
    void concurrentWritersCannotInterleaveFrames() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(8);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int index = worker;
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int number = 0; number < 40; number++) {
                        JsonUtil.writeMessage(output, new Message(MessageType.ACK,
                                index + "-" + number, null, payload().put("text", "x".repeat(500))));
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        var input = input(bytes.toByteArray());
        var correlations = new HashSet<String>();
        Message next;
        while ((next = JsonUtil.readMessage(input)) != null) {
            assertTrue(correlations.add(next.correlationId()), "Duplicate frame");
        }
        assertEquals(320, correlations.size());
    }
}
