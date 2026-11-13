package lk.gramalink.shared;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransportCodecTest {
    private static final UUID TRANSFER = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    private static byte[] encode(FileChunk chunk) throws IOException {
        var bytes = new ByteArrayOutputStream();
        TransportCodec.writeChunk(new DataOutputStream(bytes), chunk);
        return bytes.toByteArray();
    }

    private static TransportFrame decode(byte[] bytes) throws IOException {
        return TransportCodec.readFrame(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static FileChunk chunk() {
        return new FileChunk(TRANSFER, 7, true, new byte[]{0, 1, (byte) 0xff, (byte) 0x80});
    }

    @Test
    void binaryWireFormatUsesExplicitTypeUuidAndBigEndianFields() throws IOException {
        byte[] encoded = encode(chunk());
        var fields = ByteBuffer.wrap(encoded);
        assertEquals(Integer.MIN_VALUE, fields.getInt());
        assertEquals(31 + 4, fields.getInt());
        assertEquals(1, fields.get());
        assertEquals(1, fields.get());
        assertEquals(TRANSFER.getMostSignificantBits(), fields.getLong());
        assertEquals(TRANSFER.getLeastSignificantBits(), fields.getLong());
        assertEquals(7, fields.getLong());
        assertEquals(1, fields.get());
        assertEquals(4, fields.getInt());
        byte[] data = new byte[4];
        fields.get(data);
        assertArrayEquals(chunk().data(), data);
        assertFalse(fields.hasRemaining());
        assertEquals(chunk(), decode(encoded));
        assertEquals(MessageType.FILE_CHUNK, chunk().type());
    }

    @Test
    void existingJsonBytesRemainUnchangedAndCanBeDecodedByEitherReader() throws IOException {
        Message message = new Message(MessageType.HEARTBEAT, "heartbeat-1", null,
                JsonNodeFactory.instance.objectNode().put("text", "Unicode \u0d86\u0dba\u0dd4"));
        var bytes = new ByteArrayOutputStream();
        JsonUtil.writeMessage(new DataOutputStream(bytes), message);
        byte[] encoded = bytes.toByteArray();

        assertTrue(ByteBuffer.wrap(encoded).getInt() > 0);
        assertEquals(message, JsonUtil.readMessage(new DataInputStream(new ByteArrayInputStream(encoded))));
        assertEquals(new TransportFrame.JsonFrame(message), decode(encoded));
    }

    @Test
    void jsonAndConsecutiveBinaryChunksStaySeparateOnOneStream() throws IOException {
        Message control = new Message(MessageType.FILE_META, JsonNodeFactory.instance.objectNode());
        Message heartbeat = new Message(MessageType.HEARTBEAT, JsonNodeFactory.instance.objectNode());
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        JsonUtil.writeMessage(output, control);
        FileChunk first = new FileChunk(TRANSFER, 0, false, new byte[]{1, 2});
        FileChunk second = new FileChunk(TRANSFER, 1, true, new byte[]{3});
        TransportCodec.writeChunk(output, first);
        JsonUtil.writeMessage(output, heartbeat);
        TransportCodec.writeChunk(output, second);
        var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));

        assertEquals(new TransportFrame.JsonFrame(control), TransportCodec.readFrame(input));
        assertEquals(first, TransportCodec.readFrame(input));
        assertEquals(new TransportFrame.JsonFrame(heartbeat), TransportCodec.readFrame(input));
        assertEquals(second, TransportCodec.readFrame(input));
        assertNull(TransportCodec.readFrame(input));
    }

    @Test
    void fragmentedBinaryReadsAreReadFully() throws IOException {
        var fragmented = new ByteArrayInputStream(encode(chunk())) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        assertEquals(chunk(), TransportCodec.readFrame(new DataInputStream(fragmented)));
    }

    @Test
    void maximumChunkAndEmptyFileMarkerHaveBoundedFrames() throws IOException {
        FileChunk maximum = new FileChunk(TRANSFER, 0, true, new byte[65536]);
        byte[] encoded = encode(maximum);
        assertEquals(65575, encoded.length);
        assertEquals(maximum, decode(encoded));
        FileChunk empty = new FileChunk(TRANSFER, 0, true, new byte[0]);
        assertEquals(39, encode(empty).length);
        assertEquals(empty, decode(encode(empty)));
    }

    @Test
    void immutableChunkDoesNotExposeItsBackingBuffer() {
        byte[] data = {1, 2, 3};
        FileChunk chunk = new FileChunk(TRANSFER, 0, true, data);
        data[0] = 9;
        byte[] returned = chunk.data();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.data());
        assertEquals(3, chunk.dataLength());
    }

    @Test
    void invalidChunksAreRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(TRANSFER, 0, true, new byte[65537]));
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(TRANSFER, -1, true, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(new UUID(0, 0), 0, true, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(TRANSFER, 0, false, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new FileChunk(TRANSFER, 1, true, new byte[0]));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -2, 1048577, Integer.MAX_VALUE})
    void invalidOuterPrefixesFailBeforeReadingABody(int prefix) throws IOException {
        var bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(prefix);
        IOException exception = assertThrows(IOException.class, () -> decode(bytes.toByteArray()));
        assertFalse(exception instanceof EOFException);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 30, 65568, Integer.MAX_VALUE})
    void invalidBinaryBodySizesAreRejectedWithoutAllocatingOrWaiting(int bodyLength) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(TransportCodec.BINARY_MARKER);
        output.writeInt(bodyLength);
        IOException exception = assertThrows(IOException.class, () -> decode(bytes.toByteArray()));
        assertFalse(exception instanceof EOFException);
    }

    @Test
    void unknownBinaryVersionsTypesAndFlagsAreRejected() throws IOException {
        for (int offset : new int[]{8, 9, 34}) {
            byte[] invalid = encode(chunk());
            invalid[offset] = 2;
            assertThrows(IOException.class, () -> decode(invalid));
        }
    }

    @Test
    void invalidWireUuidSequenceAndChunkLengthsAreRejected() throws IOException {
        byte[] nilId = encode(chunk());
        Arrays.fill(nilId, 10, 26, (byte) 0);
        assertThrows(IOException.class, () -> decode(nilId));
        byte[] negativeSequence = encode(chunk());
        ByteBuffer.wrap(negativeSequence).putLong(26, -1);
        assertThrows(IOException.class, () -> decode(negativeSequence));

        for (int invalidLength : new int[]{-1, 0, 3, 5, 65537, Integer.MAX_VALUE}) {
            byte[] invalid = encode(chunk());
            ByteBuffer.wrap(invalid).putInt(35, invalidLength);
            assertThrows(IOException.class, () -> decode(invalid));
        }
    }

    @Test
    void truncatedFramesAreNeverMistakenForCleanEof() throws IOException {
        byte[] encoded = encode(chunk());
        for (int length = 1; length < encoded.length; length++) {
            byte[] incomplete = Arrays.copyOf(encoded, length);
            assertThrows(EOFException.class, () -> decode(incomplete), "Truncation at byte " + length);
        }
        assertNull(decode(new byte[0]));
    }

    @Test
    void legacyJsonOnlyReaderRejectsBinaryMarkerSafely() throws IOException {
        assertThrows(IOException.class,
                () -> JsonUtil.readMessage(new DataInputStream(new ByteArrayInputStream(encode(chunk())))));
    }
}
