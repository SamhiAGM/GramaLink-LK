package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;

import static lk.gramalink.client.FileTransferProgress.*;
import static org.junit.jupiter.api.Assertions.*;

class FileTransferProgressTest {
    private static Message ready(UUID id, String correlation, Direction direction, long bytes) {
        return new Message(MessageType.FILE_META, correlation, null, JsonNodeFactory.instance.objectNode()
                .put("status", "READY").put("transferId", id.toString()).put("direction", direction.name())
                .put("totalBytes", bytes).put("maxChunkBytes", FileChunk.MAX_CHUNK_BYTES));
    }
    private static Message ack(UUID id, long sequence, long bytes, boolean complete) {
        return new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode().put("transferId", id.toString())
                .put("sequence", sequence).put("receivedBytes", bytes).put("complete", complete).put("stage", "TRANSPORT_RECEIVED"));
    }
    private static final class Fixture {
        // Test-only verified-session stand-in, never used by production login or a preview.
        final AtomicBoolean authorized = new AtomicBoolean(true);
        final AtomicLong generation = new AtomicLong(1);
        final List<Snapshot> updates = new ArrayList<>();
        final FileTransferProgress tracker = new FileTransferProgress(authorized::get, generation::get, Runnable::run, updates::add);
        UUID negotiated(Direction direction, long total) throws Exception {
            UUID id = UUID.randomUUID(); String correlation = UUID.randomUUID().toString();
            tracker.expectReady(generation.get(), correlation, direction, total);
            assertTrue(tracker.acceptControl(generation.get(), ready(id, correlation, direction, total))); return id;
        }
        Snapshot snapshot(UUID id) { return tracker.snapshot(id).orElseThrow(); }
    }

    @Test void requiresCurrentAuthenticatedSessionAndCorrelatedReady() throws Exception {
        Fixture f = new Fixture(); f.authorized.set(false);
        assertThrows(IOException.class, () -> f.tracker.expectReady(1, "request", Direction.UPLOAD, 3));
        UUID id = UUID.randomUUID();
        assertFalse(f.tracker.acceptControl(1, ready(id, "unsolicited", Direction.UPLOAD, 3)));
        f.authorized.set(true); f.tracker.expectReady(1, "expected", Direction.UPLOAD, 3);
        assertFalse(f.tracker.acceptControl(1, ready(id, "wrong-id", Direction.UPLOAD, 3)));
        assertTrue(f.tracker.snapshot(id).isEmpty());
        assertTrue(f.tracker.acceptControl(1, ready(id, "expected", Direction.UPLOAD, 3)));
        assertEquals(State.READY, f.snapshot(id).state()); assertEquals(0, f.snapshot(id).confirmedBytes());
        assertThrows(IOException.class, () -> f.tracker.expectReady(0, "stale", Direction.UPLOAD, 3));
    }

    @Test void rejectsMalformedAndMismatchedReadyWithoutAuthorizingBytes() throws Exception {
        for (String invalid : List.of("status", "direction", "totalBytes", "maxChunkBytes", "transferId", "noncanonicalUUID", "token")) {
            Fixture f = new Fixture(); UUID id = UUID.randomUUID(); f.tracker.expectReady(1, "expected", Direction.UPLOAD, 3);
            Message response = ready(id, "expected", Direction.UPLOAD, 3);
            switch (invalid) {
                case "status" -> response.payload().put("status", "SUCCESS");
                case "direction" -> response.payload().put("direction", "DOWNLOAD");
                case "totalBytes" -> response.payload().put("totalBytes", 4);
                case "maxChunkBytes" -> response.payload().put("maxChunkBytes", FileChunk.MAX_CHUNK_BYTES + 1);
                case "transferId" -> response.payload().put("transferId", "00000000-0000-0000-0000-000000000000");
                case "noncanonicalUUID" -> response.payload().put("transferId", "0-0-0-0-1");
                case "token" -> response = new Message(response.type(), response.correlationId(), "unverified", response.payload());
            }
            Message malformed = response;
            assertThrows(IOException.class, () -> f.tracker.acceptControl(1, malformed), invalid);
            assertTrue(f.tracker.snapshot(id).isEmpty()); assertEquals(State.FAILED, f.updates.getLast().state());
        }
    }

    @Test void uploadProgressAdvancesOnlyForExactDocumentedTransportAck() throws Exception {
        Fixture f = new Fixture(); UUID id = f.negotiated(Direction.UPLOAD, 3);
        f.tracker.markUploadChunk(1, new FileChunk(id, 0, false, new byte[]{1, 2}));
        assertEquals(0, f.snapshot(id).confirmedBytes());
        assertTrue(f.tracker.acceptControl(1, ack(id, 0, 2, false)));
        assertEquals(2, f.snapshot(id).confirmedBytes()); assertEquals(2.0 / 3, f.snapshot(id).fraction());
        f.tracker.markUploadChunk(1, new FileChunk(id, 1, true, new byte[]{3}));
        assertEquals(2, f.snapshot(id).confirmedBytes());
        assertTrue(f.tracker.acceptControl(1, ack(id, 1, 3, true)));
        assertEquals(State.TRANSPORT_COMPLETE, f.snapshot(id).state()); assertEquals(1, f.snapshot(id).fraction());
        assertTrue(f.snapshot(id).detail().contains("durable storage is unconfirmed"));
        assertThrows(IOException.class, () -> f.tracker.markUploadChunk(1, new FileChunk(id, 2, true, new byte[]{4})));
    }

    @Test void rejectsAckWithWrongSequenceSizeCompletionStageOrType() throws Exception {
        for (String invalid : List.of("sequence", "receivedBytes", "complete", "stage", "numericType")) {
            Fixture f = new Fixture(); UUID id = f.negotiated(Direction.UPLOAD, 3);
            f.tracker.markUploadChunk(1, new FileChunk(id, 0, true, new byte[]{1, 2, 3}));
            Message response = ack(id, 0, 3, true);
            switch (invalid) {
                case "sequence" -> response.payload().put("sequence", 1);
                case "receivedBytes" -> response.payload().put("receivedBytes", 4);
                case "complete" -> response.payload().put("complete", false);
                case "stage" -> response.payload().put("stage", "STORED");
                case "numericType" -> response.payload().put("sequence", "0");
            }
            assertThrows(IOException.class, () -> f.tracker.acceptControl(1, response), invalid);
            assertEquals(State.FAILED, f.snapshot(id).state()); assertEquals(0, f.snapshot(id).confirmedBytes());
        }
    }

    @Test void oneOutstandingChunkBoundsMetadataAndPreventsUnsupportedPipelining() throws Exception {
        Fixture f = new Fixture(); UUID id = f.negotiated(Direction.UPLOAD, 2);
        f.tracker.markUploadChunk(1, new FileChunk(id, 0, false, new byte[]{1}));
        assertThrows(IOException.class, () -> f.tracker.markUploadChunk(1, new FileChunk(id, 1, true, new byte[]{2})));
        assertEquals(State.FAILED, f.snapshot(id).state()); assertEquals(0, f.snapshot(id).confirmedBytes());
    }

    @Test void downloadsValidateSharedSequenceAndNeverClaimLocalStorage() throws Exception {
        Fixture f = new Fixture(); UUID id = f.negotiated(Direction.DOWNLOAD, 3);
        Snapshot first = f.tracker.acceptDownloadChunk(1, new FileChunk(id, 0, false, new byte[]{1, 2}));
        assertEquals(2, first.confirmedBytes()); assertEquals(State.IN_PROGRESS, first.state());
        Snapshot last = f.tracker.acceptDownloadChunk(1, new FileChunk(id, 1, true, new byte[]{3}));
        assertEquals(State.TRANSPORT_COMPLETE, last.state()); assertEquals(3, last.confirmedBytes());
        assertTrue(last.detail().contains("durable local storage is unconfirmed"));
        assertThrows(IOException.class, () -> f.tracker.acceptDownloadChunk(1, new FileChunk(id, 2, true, new byte[]{4})));
        UUID invalid = f.negotiated(Direction.DOWNLOAD, 3);
        assertThrows(IOException.class, () -> f.tracker.acceptDownloadChunk(1, new FileChunk(invalid, 1, true, new byte[]{1, 2, 3})));
        assertEquals(State.FAILED, f.snapshot(invalid).state()); assertEquals(0, f.snapshot(invalid).confirmedBytes());
    }

    @Test void handlesTheAuthorizedEmptyFileExceptionWithoutInventedBytes() throws Exception {
        Fixture f = new Fixture(); UUID upload = f.negotiated(Direction.UPLOAD, 0);
        assertEquals(0, f.snapshot(upload).fraction());
        f.tracker.markUploadChunk(1, new FileChunk(upload, 0, true, new byte[0]));
        assertTrue(f.tracker.acceptControl(1, ack(upload, 0, 0, true)));
        assertEquals(1, f.snapshot(upload).fraction()); assertEquals(0, f.snapshot(upload).confirmedBytes());
        UUID download = f.negotiated(Direction.DOWNLOAD, 0);
        assertEquals(State.TRANSPORT_COMPLETE, f.tracker.acceptDownloadChunk(1, new FileChunk(download, 0, true, new byte[0])).state());
    }

    @Test void disconnectMarksUnfinishedTransfersAndNegotiationsIncompleteWithoutResume() throws Exception {
        Fixture f = new Fixture(); UUID download = f.negotiated(Direction.DOWNLOAD, 3);
        f.tracker.acceptDownloadChunk(1, new FileChunk(download, 0, false, new byte[]{1}));
        f.tracker.expectReady(1, "pending", Direction.UPLOAD, 10);
        f.authorized.set(false); f.tracker.disconnected(1);
        assertEquals(State.INCOMPLETE, f.snapshot(download).state()); assertEquals(1, f.snapshot(download).confirmedBytes());
        assertTrue(f.updates.stream().anyMatch(update -> "pending".equals(update.correlationId()) && update.state() == State.INCOMPLETE));
        f.generation.set(2); f.authorized.set(true);
        assertThrows(IOException.class, () -> f.tracker.acceptDownloadChunk(2, new FileChunk(download, 1, true, new byte[]{2, 3})));
        assertFalse(f.tracker.acceptControl(1, ack(download, 0, 1, false)));
        UUID fresh = f.negotiated(Direction.DOWNLOAD, 1);
        assertEquals(State.TRANSPORT_COMPLETE, f.tracker.acceptDownloadChunk(2, new FileChunk(fresh, 0, true, new byte[]{1})).state());
    }

    @Test void unrelatedErrorsAndHeartbeatAcksDoNotAdvanceOrAbortAFile() throws Exception {
        Fixture f = new Fixture(); UUID id = f.negotiated(Direction.UPLOAD, 1);
        assertFalse(f.tracker.acceptControl(1, new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode().put("acknowledgedType", "HEARTBEAT"))));
        assertFalse(f.tracker.acceptControl(1, new Message(MessageType.ERROR, JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED"))));
        assertFalse(f.tracker.acceptControl(1, ack(UUID.randomUUID(), 0, 1, true)));
        assertEquals(State.READY, f.snapshot(id).state());
        assertTrue(f.tracker.acceptControl(1, new Message(MessageType.ERROR, JsonNodeFactory.instance.objectNode().put("transferId", id.toString()).put("code", "FILE_TRANSFER_FAILED"))));
        assertEquals(State.FAILED, f.snapshot(id).state());
    }

    @Test void defaultBackendNotImplementedResponseFailsNegotiationWithoutFakeGrant() throws Exception {
        Fixture f = new Fixture(); f.tracker.expectReady(1, "file-request", Direction.UPLOAD, 1);
        Message unavailable = new Message(MessageType.ERROR, "file-request", null,
                JsonNodeFactory.instance.objectNode().put("code", "NOT_IMPLEMENTED").put("message", "File transfer requires backend authorization."));
        assertTrue(f.tracker.acceptControl(1, unavailable)); assertTrue(f.tracker.snapshots().isEmpty());
        assertEquals(State.FAILED, f.updates.getLast().state()); assertTrue(f.updates.getLast().detail().contains("NOT_IMPLEMENTED"));
    }

    @Test void queuedProgressFromOldGenerationIsNotDeliveredToReplacementSession() throws Exception {
        AtomicBoolean authorized = new AtomicBoolean(true); AtomicLong generation = new AtomicLong(1);
        List<Runnable> queued = new ArrayList<>(); List<Snapshot> updates = new ArrayList<>();
        FileTransferProgress tracker = new FileTransferProgress(authorized::get, generation::get, queued::add, updates::add);
        UUID id = UUID.randomUUID(); tracker.expectReady(1, "file", Direction.UPLOAD, 1);
        tracker.acceptControl(1, ready(id, "file", Direction.UPLOAD, 1));
        generation.set(2); queued.forEach(Runnable::run); assertTrue(updates.isEmpty());
    }

    @Test void malformedCorrelatedServerErrorsFailOnlyTheirKnownTransfer() throws Exception {
        Fixture f = new Fixture(); f.tracker.expectReady(1, "pending", Direction.UPLOAD, 1);
        assertThrows(IOException.class, () -> f.tracker.acceptControl(1,
                new Message(MessageType.ERROR, "pending", null, JsonNodeFactory.instance.objectNode())));
        assertEquals(State.FAILED, f.updates.getLast().state());
        UUID affected = f.negotiated(Direction.UPLOAD, 1), untouched = f.negotiated(Direction.UPLOAD, 1);
        assertThrows(IOException.class, () -> f.tracker.acceptControl(1, new Message(MessageType.ERROR,
                JsonNodeFactory.instance.objectNode().put("transferId", affected.toString()).put("code", 123))));
        assertEquals(State.FAILED, f.snapshot(affected).state()); assertEquals(State.READY, f.snapshot(untouched).state());
    }

    @Test void boundsLiveNegotiationsAndRecentTerminalHistory() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) f.tracker.expectReady(1, "pending-" + i, Direction.UPLOAD, 1);
        assertThrows(IOException.class, () -> f.tracker.expectReady(1, "fifth", Direction.UPLOAD, 1));
        f.tracker.disconnected(1);
        for (int i = 0; i < 70; i++) {
            UUID id = f.negotiated(Direction.UPLOAD, 0);
            f.tracker.markUploadChunk(1, new FileChunk(id, 0, true, new byte[0])); f.tracker.acceptControl(1, ack(id, 0, 0, true));
        }
        assertTrue(f.tracker.snapshots().size() <= 64);
    }
}
