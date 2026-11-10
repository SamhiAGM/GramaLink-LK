package lk.gramalink.shared;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Decodes unchanged JSON frames and the explicitly typed binary extension. */
public final class TransportCodec {
    public static final int BINARY_MARKER = Integer.MIN_VALUE;
    public static final int BINARY_VERSION = 1;
    public static final int FILE_CHUNK_TYPE = 1;
    public static final int CHUNK_HEADER_BYTES = 31;
    public static final int MAX_BINARY_BODY_BYTES = CHUNK_HEADER_BYTES + FileChunk.MAX_CHUNK_BYTES;

    private TransportCodec() {
    }

    /** Null means clean EOF before the next frame starts; truncation throws EOFException. */
    public static TransportFrame readFrame(DataInputStream input) throws IOException {
        Integer prefix = JsonUtil.readLength(input);
        if (prefix == null) {
            return null;
        }
        if (prefix > 0) {
            return new TransportFrame.JsonFrame(JsonUtil.readPayload(input, prefix));
        }
        if (prefix != BINARY_MARKER) {
            throw new IOException("Invalid transport frame prefix.");
        }

        int bodyLength = input.readInt();
        if (bodyLength < CHUNK_HEADER_BYTES || bodyLength > MAX_BINARY_BODY_BYTES) {
            throw new IOException("Invalid binary frame body length.");
        }
        if (input.readUnsignedByte() != BINARY_VERSION) {
            throw new IOException("Unsupported binary frame version.");
        }
        if (input.readUnsignedByte() != FILE_CHUNK_TYPE) {
            throw new IOException("Unsupported binary frame type.");
        }
        UUID transferId = new UUID(input.readLong(), input.readLong());
        long sequence = input.readLong();
        int flags = input.readUnsignedByte();
        if ((flags & ~1) != 0) {
            throw new IOException("Unsupported binary chunk flags.");
        }
        boolean last = (flags & 1) != 0;
        int dataLength = input.readInt();
        if (dataLength < 0 || dataLength > FileChunk.MAX_CHUNK_BYTES
                || bodyLength != CHUNK_HEADER_BYTES + dataLength) {
            throw new IOException("Invalid binary chunk data length.");
        }
        if (sequence < 0 || (transferId.getMostSignificantBits() == 0 && transferId.getLeastSignificantBits() == 0)
                || (dataLength == 0 && !(sequence == 0 && last))) {
            throw new IOException("Invalid binary chunk identifier, sequence, or empty-chunk marker.");
        }
        byte[] data = new byte[dataLength];
        input.readFully(data);
        return new FileChunk(transferId, sequence, last, data);
    }

    /** Uses the same output monitor as JsonUtil; server callers use ConnectionWriter. */
    public static void writeChunk(DataOutputStream output, FileChunk chunk) throws IOException {
        Objects.requireNonNull(output, "Output is required.");
        Objects.requireNonNull(chunk, "Chunk is required.");
        byte[] data = chunk.data();
        synchronized (output) {
            output.writeInt(BINARY_MARKER);
            output.writeInt(CHUNK_HEADER_BYTES + data.length);
            output.writeByte(BINARY_VERSION);
            output.writeByte(FILE_CHUNK_TYPE);
            output.writeLong(chunk.transferId().getMostSignificantBits());
            output.writeLong(chunk.transferId().getLeastSignificantBits());
            output.writeLong(chunk.sequence());
            output.writeByte(chunk.last() ? 1 : 0);
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }
}
