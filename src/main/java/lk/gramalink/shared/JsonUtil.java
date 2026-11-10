package lk.gramalink.shared;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared JSON serialization and four-byte, big-endian TCP framing. */
public final class JsonUtil {
    /** Maximum JSON size in UTF-8 bytes (1 MiB), excluding the four-byte header. */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    // Fully configured before publication; reused safely by all client workers.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private JsonUtil() {
    }

    public static String toJson(Message message) throws IOException {
        return new String(serialize(message), StandardCharsets.UTF_8);
    }

    public static Message fromJson(String json) throws IOException {
        if (json == null) {
            throw new IOException("JSON message is required.");
        }
        return deserialize(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null only if EOF occurs before the next header starts. */
    public static Message readMessage(DataInputStream input) throws IOException {
        int first = input.read();
        if (first == -1) {
            return null;
        }

        // Reading the first byte separately distinguishes clean EOF from truncation.
        // readUnsignedByte throws EOFException if the remaining header is incomplete.
        int length = (first << 24)
                | (input.readUnsignedByte() << 16)
                | (input.readUnsignedByte() << 8)
                | input.readUnsignedByte();
        validateLength(length);

        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return deserialize(bytes);
    }

    /** Use one shared output wrapper per socket; this lock covers the entire frame. */
    public static void writeMessage(DataOutputStream output, Message message) throws IOException {
        byte[] bytes = serialize(message);
        synchronized (output) {
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }
    }

    private static byte[] serialize(Message message) throws IOException {
        if (message == null) {
            throw new IOException("Message is required.");
        }
        byte[] bytes = MAPPER.writeValueAsBytes(message);
        validateLength(bytes.length);
        return bytes;
    }

    private static Message deserialize(byte[] bytes) throws IOException {
        validateLength(bytes.length);
        // Explicitly reject invalid UTF-8 rather than replacing corrupt bytes.
        String json = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        Message message = MAPPER.readValue(json, Message.class);
        if (message == null) {
            throw new IOException("JSON message must be an object.");
        }
        return message;
    }

    private static void validateLength(int length) throws IOException {
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            throw new IOException("Invalid JSON payload length: " + length
                    + ". Expected 1 to " + MAX_PAYLOAD_BYTES + " bytes.");
        }
    }
}
