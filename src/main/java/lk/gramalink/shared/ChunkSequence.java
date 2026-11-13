package lk.gramalink.shared;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Bounded transfer progress after backend authorization; retains no file bytes. */
public final class ChunkSequence {
    private final UUID transferId;
    private final long totalBytes;
    private long nextSequence;
    private long receivedBytes;
    private boolean complete;

    public ChunkSequence(UUID transferId, long totalBytes) {
        this.transferId = Objects.requireNonNull(transferId, "Transfer ID is required.");
        if ((transferId.getMostSignificantBits() == 0 && transferId.getLeastSignificantBits() == 0)
                || totalBytes < 0) {
            throw new IllegalArgumentException("A non-nil transfer ID and nonnegative total size are required.");
        }
        this.totalBytes = totalBytes;
    }

    public synchronized void validate(FileChunk chunk) throws IOException {
        Objects.requireNonNull(chunk, "Chunk is required.");
        if (complete) {
            throw new IOException("Transfer has already completed.");
        }
        if (!transferId.equals(chunk.transferId())) {
            throw new IOException("Chunk transfer ID does not match the authorized transfer.");
        }
        if (chunk.sequence() != nextSequence) {
            throw new IOException("Unexpected chunk sequence.");
        }
        if (chunk.dataLength() > totalBytes - receivedBytes) {
            throw new IOException("Chunk exceeds the authorized total size.");
        }
        long after = receivedBytes + chunk.dataLength();
        if (chunk.last() != (after == totalBytes)) {
            throw new IOException("Final-chunk flag does not match the authorized total size.");
        }
        if (nextSequence == Long.MAX_VALUE && !chunk.last()) {
            throw new IOException("Chunk sequence limit reached.");
        }
    }

    public synchronized void accept(FileChunk chunk) throws IOException {
        validate(chunk);
        receivedBytes += chunk.dataLength();
        if (nextSequence < Long.MAX_VALUE) {
            nextSequence++;
        }
        complete = chunk.last();
    }

    public synchronized long nextSequence() {
        return nextSequence;
    }

    public synchronized long receivedBytes() {
        return receivedBytes;
    }

    public synchronized boolean complete() {
        return complete;
    }
}
