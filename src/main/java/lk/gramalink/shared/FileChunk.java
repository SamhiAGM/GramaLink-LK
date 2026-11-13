package lk.gramalink.shared;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** One immutable, bounded binary chunk; the transfer ID is not authorization. */
public record FileChunk(UUID transferId, long sequence, boolean last, byte[] data) implements TransportFrame {
    public static final int MAX_CHUNK_BYTES = 64 * 1024;

    public FileChunk {
        Objects.requireNonNull(transferId, "Transfer ID is required.");
        Objects.requireNonNull(data, "Chunk data is required.");
        if (transferId.getMostSignificantBits() == 0 && transferId.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException("Transfer ID must not be nil.");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("Chunk sequence must not be negative.");
        }
        if (data.length > MAX_CHUNK_BYTES || (data.length == 0 && !(sequence == 0 && last))) {
            throw new IllegalArgumentException("Chunk data must contain 1 to 65536 bytes, or be the empty final chunk at sequence 0.");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public int dataLength() {
        return data.length;
    }

    public MessageType type() {
        return MessageType.FILE_CHUNK;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FileChunk chunk && transferId.equals(chunk.transferId)
                && sequence == chunk.sequence && last == chunk.last && Arrays.equals(data, chunk.data);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(transferId, sequence, last) + Arrays.hashCode(data);
    }
}
