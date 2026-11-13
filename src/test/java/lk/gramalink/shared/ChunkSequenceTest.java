package lk.gramalink.shared;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSequenceTest {
    private static final UUID TRANSFER = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test
    void orderedChunksAdvanceWithoutRetainingFileData() throws IOException {
        var progress = new ChunkSequence(TRANSFER, 5);
        var first = new FileChunk(TRANSFER, 0, false, new byte[]{1, 2, 3});
        progress.validate(first);
        assertEquals(0, progress.nextSequence());
        assertEquals(0, progress.receivedBytes());
        progress.accept(first);
        assertEquals(1, progress.nextSequence());
        assertEquals(3, progress.receivedBytes());
        assertFalse(progress.complete());
        progress.accept(new FileChunk(TRANSFER, 1, true, new byte[]{4, 5}));
        assertEquals(2, progress.nextSequence());
        assertEquals(5, progress.receivedBytes());
        assertTrue(progress.complete());
    }

    @Test
    void wrongTransferIdsSkippedAndDuplicateSequencesDoNotAdvanceProgress() throws IOException {
        var progress = new ChunkSequence(TRANSFER, 2);
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(UUID.randomUUID(), 0, false, new byte[]{1})));
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 1, false, new byte[]{1})));
        assertEquals(0, progress.receivedBytes());
        var first = new FileChunk(TRANSFER, 0, false, new byte[]{1});
        progress.accept(first);
        assertThrows(IOException.class, () -> progress.accept(first));
        assertEquals(1, progress.receivedBytes());
        assertEquals(1, progress.nextSequence());
    }

    @Test
    void declaredTotalAndFinalFlagMustMatchExactly() throws IOException {
        var progress = new ChunkSequence(TRANSFER, 2);
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, true, new byte[]{1})));
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, false, new byte[]{1, 2})));
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, true, new byte[]{1, 2, 3})));
        assertEquals(0, progress.receivedBytes());
        progress.accept(new FileChunk(TRANSFER, 0, true, new byte[]{1, 2}));
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 1, true, new byte[]{3})));
    }

    @Test
    void zeroByteTransferNeedsItsExplicitEmptyFinalMarker() throws IOException {
        var progress = new ChunkSequence(TRANSFER, 0);
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, true, new byte[]{1})));
        progress.accept(new FileChunk(TRANSFER, 0, true, new byte[0]));
        assertTrue(progress.complete());
        assertEquals(0, progress.receivedBytes());
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, true, new byte[0])));
    }

    @Test
    void emptyFinalMarkerCannotCompleteANonemptyTransfer() {
        var progress = new ChunkSequence(TRANSFER, 1);
        assertThrows(IOException.class,
                () -> progress.accept(new FileChunk(TRANSFER, 0, true, new byte[0])));
    }

    @Test
    void maximumDeclaredSizeDoesNotOverflowTheSizeCheck() throws IOException {
        var progress = new ChunkSequence(TRANSFER, Long.MAX_VALUE);
        progress.accept(new FileChunk(TRANSFER, 0, false, new byte[FileChunk.MAX_CHUNK_BYTES]));
        assertEquals(FileChunk.MAX_CHUNK_BYTES, progress.receivedBytes());
        assertFalse(progress.complete());
        assertThrows(IllegalArgumentException.class, () -> new ChunkSequence(TRANSFER, -1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkSequence(new UUID(0, 0), 0));
        assertThrows(NullPointerException.class, () -> new ChunkSequence(null, 0));
    }
}
