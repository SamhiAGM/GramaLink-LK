package lk.gramalink.client;

import com.fasterxml.jackson.databind.JsonNode;
import lk.gramalink.shared.ChunkSequence;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Bounded, session-scoped progress for Samhi's documented mixed-frame protocol.
 * This class never creates FILE_META requests, authenticates a user, stores bytes,
 * resumes a transfer, or interprets transport completion as durable storage.
 */
public final class FileTransferProgress {
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum State { NEGOTIATING, READY, IN_PROGRESS, TRANSPORT_COMPLETE, INCOMPLETE, FAILED }
    public record Snapshot(long generation, UUID transferId, String correlationId, Direction direction,
                           long totalBytes, long confirmedBytes, State state, String detail) {
        public double fraction() {
            return totalBytes == 0 ? (state == State.TRANSPORT_COMPLETE ? 1 : 0) : (double) confirmedBytes / totalBytes;
        }
    }
    private record Expected(long generation, String correlationId, Direction direction, long totalBytes) { }
    private record AwaitingAck(long sequence, long endBytes, boolean last) { }
    private static final class Transfer {
        final Expected expected;
        final UUID id;
        final ChunkSequence sequence;
        State state = State.READY;
        long confirmedBytes;
        AwaitingAck awaiting;
        String detail = "READY: authorized transport negotiated; no file bytes transferred.";
        Transfer(Expected expected, UUID id) {
            this.expected = expected; this.id = id; sequence = new ChunkSequence(id, expected.totalBytes());
        }
        Snapshot snapshot() {
            return new Snapshot(expected.generation(), id, expected.correlationId(), expected.direction(),
                    expected.totalBytes(), confirmedBytes, state, detail);
        }
        boolean active() { return state == State.READY || state == State.IN_PROGRESS; }
    }
    private static final int MAX_ACTIVE = 4;
    private static final int MAX_RECENT = 64;
    private final BooleanSupplier authorized;
    private final LongSupplier currentGeneration;
    private final Executor callbacks;
    private final Consumer<Snapshot> listener;
    private final Map<String, Expected> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Transfer> transfers = new LinkedHashMap<>();

    /** Supply the real current-session authorization gate; no local auth flag is accepted. */
    public FileTransferProgress(BooleanSupplier authorized, LongSupplier currentGeneration,
                                Executor callbacks, Consumer<Snapshot> listener) {
        this.authorized = Objects.requireNonNull(authorized); this.currentGeneration = Objects.requireNonNull(currentGeneration);
        this.callbacks = Objects.requireNonNull(callbacks); this.listener = Objects.requireNonNull(listener);
    }

    /**
     * Register immediately before an agreed backend FILE_META request is sent.
     * Metadata fields/destination permission must come from the future backend adapter.
     */
    public synchronized void expectReady(long generation, String correlationId, Direction direction, long totalBytes) throws IOException {
        requireSession(generation);
        if (correlationId == null || correlationId.isBlank() || direction == null || totalBytes < 0)
            throw new IllegalArgumentException("A correlation ID, direction and nonnegative expected size are required.");
        if (pending.containsKey(correlationId) || transfers.values().stream().anyMatch(t -> t.active() && t.expected.correlationId().equals(correlationId)))
            throw new IOException("File negotiation correlation ID is already active.");
        if (pending.size() + transfers.values().stream().filter(Transfer::active).count() >= MAX_ACTIVE)
            throw new IOException("At most four negotiated transfers may be active on one connection.");
        Expected expected = new Expected(generation, correlationId, direction, totalBytes);
        pending.put(correlationId, expected);
        publish(new Snapshot(generation, null, correlationId, direction, totalBytes, 0, State.NEGOTIATING,
                "Awaiting backend FILE_META authorization. No file bytes transferred."));
    }

    /** Returns false for unrelated messages or an obsolete/unauthorized session. */
    public synchronized boolean acceptControl(long generation, Message message) throws IOException {
        Objects.requireNonNull(message);
        if (!sessionCurrent(generation)) return false;
        if (message.type() == MessageType.ERROR) return acceptError(generation, message);
        if (message.type() == MessageType.FILE_META) {
            Expected expected = pending.get(message.correlationId());
            if (expected == null || expected.generation() != generation) return false;
            pending.remove(message.correlationId());
            try {
                if (message.token() != null || !text(message.payload(), "status").equals("READY")) throw new IOException("Invalid FILE_META readiness response.");
                UUID id = uuid(message.payload());
                if (transfers.containsKey(id)) throw new IOException("Transfer ID has already been used on this tracked connection.");
                if (!text(message.payload(), "direction").equals(expected.direction().name())
                        || number(message.payload(), "totalBytes") != expected.totalBytes()
                        || number(message.payload(), "maxChunkBytes") != FileChunk.MAX_CHUNK_BYTES)
                    throw new IOException("Negotiated file metadata does not match the requested bounded transport.");
                Transfer transfer = new Transfer(expected, id); transfers.put(id, transfer); trimHistory(); publish(transfer.snapshot()); return true;
            } catch (IOException error) {
                publish(new Snapshot(generation, null, expected.correlationId(), expected.direction(), expected.totalBytes(), 0,
                        State.FAILED, "File negotiation failed validation. No bytes were authorized for transfer."));
                throw error;
            }
        }
        if (message.type() == MessageType.ACK && message.payload().has("transferId")) {
            Transfer transfer = transfers.get(uuid(message.payload()));
            if (transfer == null || transfer.expected.generation() != generation) return false;
            try {
                AwaitingAck awaiting = transfer.awaiting;
                if (!transfer.active() || transfer.expected.direction() != Direction.UPLOAD || awaiting == null
                        || message.token() != null || !text(message.payload(), "stage").equals("TRANSPORT_RECEIVED")
                        || number(message.payload(), "sequence") != awaiting.sequence()
                        || number(message.payload(), "receivedBytes") != awaiting.endBytes()
                        || bool(message.payload(), "complete") != awaiting.last())
                    throw new IOException("File ACK does not match the one outstanding upload chunk.");
                transfer.confirmedBytes = awaiting.endBytes(); transfer.awaiting = null;
                transfer.state = awaiting.last() ? State.TRANSPORT_COMPLETE : State.IN_PROGRESS;
                transfer.detail = awaiting.last()
                        ? "TRANSPORT_RECEIVED: all upload bytes acknowledged by transport; durable storage is unconfirmed."
                        : "TRANSPORT_RECEIVED: bounded upload bytes acknowledged; durable storage is unconfirmed.";
                publish(transfer.snapshot()); return true;
            } catch (IOException error) { fail(transfer, "Invalid transport ACK; transfer stopped without a storage success claim."); throw error; }
        }
        return false;
    }

    /**
     * Reserve a chunk before writing it through NetworkClient.sendChunk. Uploads use
     * one outstanding chunk so acknowledgement validation retains no file bytes.
     * A write failure must call failTransfer/disconnect; it is never counted as ACK.
     */
    public synchronized void markUploadChunk(long generation, FileChunk chunk) throws IOException {
        requireSession(generation); Transfer transfer = active(generation, chunk.transferId(), Direction.UPLOAD);
        try {
            if (transfer.awaiting != null) throw new IOException("Wait for the previous chunk's transport acknowledgement.");
            transfer.sequence.accept(chunk);
            transfer.awaiting = new AwaitingAck(chunk.sequence(), transfer.sequence.receivedBytes(), chunk.last());
            transfer.state = State.IN_PROGRESS;
            transfer.detail = "Upload chunk queued for transport; awaiting TRANSPORT_RECEIVED acknowledgement.";
            publish(transfer.snapshot());
        } catch (IOException error) { fail(transfer, "Invalid upload chunk/order; transfer stopped."); throw error; }
    }

    /** Counts only bytes received on an authorized DOWNLOAD grant, never durable file writes. */
    public synchronized Snapshot acceptDownloadChunk(long generation, FileChunk chunk) throws IOException {
        requireSession(generation); Transfer transfer = active(generation, chunk.transferId(), Direction.DOWNLOAD);
        try {
            transfer.sequence.accept(chunk); transfer.confirmedBytes = transfer.sequence.receivedBytes();
            transfer.state = transfer.sequence.complete() ? State.TRANSPORT_COMPLETE : State.IN_PROGRESS;
            transfer.detail = transfer.sequence.complete()
                    ? "CLIENT_RECEIVED: all download bytes received; durable local storage is unconfirmed."
                    : "CLIENT_RECEIVED: bounded download bytes received; durable local storage is unconfirmed.";
            Snapshot snapshot = transfer.snapshot(); publish(snapshot); return snapshot;
        } catch (IOException error) { fail(transfer, "Invalid download chunk/order; incomplete download stopped."); throw error; }
    }

    public synchronized void failNegotiation(long generation, String correlationId, String reason) {
        Expected expected = pending.get(correlationId);
        if (expected == null || expected.generation() != generation) return;
        pending.remove(correlationId);
        publish(new Snapshot(generation, null, correlationId, expected.direction(), expected.totalBytes(), 0, State.FAILED,
                "File negotiation did not complete. " + Objects.requireNonNull(reason)));
    }
    public synchronized void failTransfer(long generation, UUID transferId, String reason) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null && transfer.expected.generation() == generation && transfer.active()) fail(transfer, reason);
    }
    /** No cross-connection resume exists: all unfinished negotiations/transfers are terminal. */
    public synchronized void disconnected(long generation) {
        var expected = pending.values().stream().filter(item -> item.generation() == generation).toList();
        expected.forEach(item -> {
            pending.remove(item.correlationId());
            publish(new Snapshot(generation, null, item.correlationId(), item.direction(), item.totalBytes(), 0, State.INCOMPLETE,
                    "Connection lost during negotiation. Fresh authentication and transfer authorization are required."));
        });
        transfers.values().stream().filter(t -> t.expected.generation() == generation && t.active()).forEach(transfer -> {
            transfer.state = State.INCOMPLETE; transfer.awaiting = null;
            transfer.detail = "Incomplete transfer: connection lost. No resume or durable-storage success is assumed."; publish(transfer.snapshot());
        });
    }
    public synchronized Optional<Snapshot> snapshot(UUID transferId) { return Optional.ofNullable(transfers.get(transferId)).map(Transfer::snapshot); }
    public synchronized List<Snapshot> snapshots() { return transfers.values().stream().map(Transfer::snapshot).toList(); }

    private boolean acceptError(long generation, Message message) throws IOException {
        Expected expected = pending.get(message.correlationId());
        if (expected != null && expected.generation() == generation) {
            String code;
            try { code = text(message.payload(), "code"); }
            catch (IOException error) { failNegotiation(generation, message.correlationId(), "Malformed server error response."); throw error; }
            failNegotiation(generation, message.correlationId(), "Server error: " + code); return true;
        }
        if (!message.payload().has("transferId")) return false;
        Transfer transfer = transfers.get(uuid(message.payload()));
        if (transfer == null || transfer.expected.generation() != generation || !transfer.active()) return false;
        String code;
        try { code = text(message.payload(), "code"); }
        catch (IOException error) { fail(transfer, "Malformed server file error; transfer stopped without a storage claim."); throw error; }
        fail(transfer, "Server file error: " + code + ". No durable storage is confirmed."); return true;
    }
    private Transfer active(long generation, UUID id, Direction direction) throws IOException {
        Transfer transfer = transfers.get(id);
        if (transfer == null || transfer.expected.generation() != generation || !transfer.active() || transfer.expected.direction() != direction)
            throw new IOException("No active authorized transfer exists for this session, ID and direction.");
        return transfer;
    }
    private boolean sessionCurrent(long generation) { return generation == currentGeneration.getAsLong() && authorized.getAsBoolean(); }
    private void requireSession(long generation) throws IOException {
        if (!sessionCurrent(generation)) throw new IOException("An active authenticated session is required for file transport.");
    }
    private void fail(Transfer transfer, String reason) {
        transfer.state = State.FAILED; transfer.awaiting = null; transfer.detail = Objects.requireNonNull(reason); publish(transfer.snapshot());
    }
    private void trimHistory() {
        var iterator = transfers.entrySet().iterator();
        while (transfers.size() > MAX_RECENT && iterator.hasNext()) if (!iterator.next().getValue().active()) iterator.remove();
    }
    private void publish(Snapshot snapshot) {
        callbacks.execute(() -> {
            if (snapshot.generation() == currentGeneration.getAsLong() && (authorized.getAsBoolean()
                    || snapshot.state() == State.INCOMPLETE || snapshot.state() == State.FAILED)) listener.accept(snapshot);
        });
    }
    private static String text(JsonNode payload, String field) throws IOException {
        JsonNode value = payload.get(field); if (value == null || !value.isTextual()) throw new IOException("Invalid file field: " + field); return value.textValue();
    }
    private static long number(JsonNode payload, String field) throws IOException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw new IOException("Invalid file field: " + field);
        return value.longValue();
    }
    private static boolean bool(JsonNode payload, String field) throws IOException {
        JsonNode value = payload.get(field); if (value == null || !value.isBoolean()) throw new IOException("Invalid file field: " + field); return value.booleanValue();
    }
    private static UUID uuid(JsonNode payload) throws IOException {
        try {
            String value = text(payload, "transferId"); UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value) || (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw new IOException("Invalid file transfer identifier.", error); }
    }
}
