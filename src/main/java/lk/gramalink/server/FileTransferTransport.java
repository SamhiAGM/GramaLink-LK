package lk.gramalink.server;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.ChunkSequence;
import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** Bounded, connection-scoped transport state. It never opens a file or trusts client identity claims. */
public final class FileTransferTransport implements AutoCloseable {
    public static final int MAX_ACTIVE_TRANSFERS = 4;

    private final Socket socket;
    private final SessionManager sessions;
    private final FileTransferService service;
    private final LinkedHashMap<UUID, Transfer> transfers = new LinkedHashMap<>();
    private boolean closed;

    private record Transfer(SessionManager.Session session, FileTransferService.Grant grant, ChunkSequence sequence) {
    }

    public FileTransferTransport(Socket socket, SessionManager sessions, FileTransferService service) {
        this.socket = Objects.requireNonNull(socket, "Connection is required.");
        this.sessions = Objects.requireNonNull(sessions, "Session manager is required.");
        this.service = Objects.requireNonNull(service, "File service is required.");
    }

    /** The backend alone interprets and validates untrusted FILE_META payload fields. */
    public synchronized Message negotiate(Message metadata) {
        Objects.requireNonNull(metadata, "Metadata is required.");
        if (metadata.type() != MessageType.FILE_META) {
            throw new IllegalArgumentException("Expected FILE_META.");
        }
        if (closed) {
            return error("TRANSFER_CLOSED", "File transport is closed.", metadata.correlationId(), null);
        }
        SessionManager.Session session = sessions.findSession(socket).orElse(null);
        if (session == null) {
            // Preserve the existing control-message error contract while AuthService is absent.
            return error("NOT_IMPLEMENTED", "File transfer requires authentication and backend authorization.",
                    metadata.correlationId(), null);
        }
        purgeStale(session);
        if (transfers.size() >= MAX_ACTIVE_TRANSFERS) {
            return error("TRANSFER_LIMIT", "Too many active transfers on this connection.", metadata.correlationId(), null);
        }
        final FileTransferService.Grant grant;
        try {
            grant = Objects.requireNonNull(service.authorize(session, metadata), "Backend must return a grant.");
        } catch (FileTransferService.UnavailableException exception) {
            return error("NOT_IMPLEMENTED", "File authorization and storage integration is not available.",
                    metadata.correlationId(), null);
        } catch (IOException | RuntimeException exception) {
            return error("FILE_TRANSFER_REJECTED", "The backend rejected the file transfer.", metadata.correlationId(), null);
        }
        if (closed || sessions.findSession(socket).orElse(null) != session) {
            abort(new Transfer(session, grant, new ChunkSequence(grant.transferId(), grant.totalBytes())));
            return error("AUTHENTICATION_REQUIRED", "Authenticated connection is no longer active.",
                    metadata.correlationId(), null);
        }
        if (transfers.containsKey(grant.transferId())) {
            return error("TRANSFER_CONFLICT", "Transfer ID is already active on this connection.",
                    metadata.correlationId(), grant.transferId());
        }
        transfers.put(grant.transferId(), new Transfer(session, grant,
                new ChunkSequence(grant.transferId(), grant.totalBytes())));
        var payload = JsonNodeFactory.instance.objectNode()
                .put("status", "READY").put("transferId", grant.transferId().toString())
                .put("direction", grant.direction().name()).put("totalBytes", grant.totalBytes())
                .put("maxChunkBytes", FileChunk.MAX_CHUNK_BYTES);
        return new Message(MessageType.FILE_META, metadata.correlationId(), null, payload);
    }

    public synchronized Message receiveChunk(FileChunk chunk) {
        Objects.requireNonNull(chunk, "Chunk is required.");
        SessionManager.Session session = sessions.findSession(socket).orElse(null);
        if (closed || session == null) {
            return error("AUTHENTICATION_REQUIRED", "An active authenticated connection is required.", null, chunk.transferId());
        }
        purgeStale(session);
        Transfer transfer = transfers.get(chunk.transferId());
        if (transfer == null) {
            return error("UNKNOWN_TRANSFER", "No authorized transfer exists on this connection.", null, chunk.transferId());
        }
        try {
            if (transfer.grant().direction() != FileTransferService.Direction.UPLOAD) {
                throw new IOException("Unexpected transfer direction.");
            }
            transfer.sequence().validate(chunk);
        } catch (IOException exception) {
            discard(transfer);
            return error("INVALID_CHUNK", "Transfer ID, sequence, size, or final flag is invalid.", null, chunk.transferId());
        }
        try {
            service.receiveChunk(session, transfer.grant(), chunk);
            if (closed || transfers.get(chunk.transferId()) != transfer
                    || sessions.findSession(socket).orElse(null) != session) {
                throw new IOException("Authenticated connection changed during chunk processing.");
            }
            transfer.sequence().accept(chunk);
        } catch (IOException | RuntimeException exception) {
            discard(transfer);
            return error("FILE_TRANSFER_FAILED", "The backend could not consume this chunk.", null, chunk.transferId());
        }
        if (transfer.sequence().complete()) {
            transfers.remove(chunk.transferId());
        }
        var payload = JsonNodeFactory.instance.objectNode().put("transferId", chunk.transferId().toString())
                .put("sequence", chunk.sequence()).put("receivedBytes", transfer.sequence().receivedBytes())
                .put("complete", transfer.sequence().complete()).put("stage", "TRANSPORT_RECEIVED");
        return new Message(MessageType.ACK, payload);
    }

    /**
     * Future trusted download service entry point. Send the READY FILE_META response
     * before calling this method. No download is initiated by the Phase 1 server.
     */
    public synchronized void sendChunk(FileChunk chunk) throws IOException {
        Objects.requireNonNull(chunk, "Chunk is required.");
        Transfer transfer = transfers.get(chunk.transferId());
        if (closed || transfer == null) {
            throw new IOException("No active authorized download transfer.");
        }
        try {
            if (transfer.grant().direction() != FileTransferService.Direction.DOWNLOAD
                    || sessions.findSession(socket).orElse(null) != transfer.session()) {
                throw new IOException("Transfer is not an active authorized download.");
            }
            transfer.sequence().validate(chunk);
            if (!sessions.sendChunkToSession(transfer.session(), chunk)) {
                throw new IOException("Authenticated connection is no longer active.");
            }
            transfer.sequence().accept(chunk);
            if (transfer.sequence().complete()) {
                transfers.remove(chunk.transferId());
            }
        } catch (IOException | RuntimeException exception) {
            discard(transfer);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to send file chunk.", exception);
        }
    }

    private void purgeStale(SessionManager.Session current) {
        for (Transfer transfer : List.copyOf(transfers.values())) {
            if (transfer.session() != current) {
                discard(transfer);
            }
        }
    }

    private void discard(Transfer transfer) {
        if (transfers.remove(transfer.grant().transferId(), transfer)) {
            abort(transfer);
        }
    }

    /** Release a download whose backend stream failed before the first chunk was written. */
    public synchronized void cancel(UUID transferId) {
        Transfer transfer = transfers.get(transferId);
        if (transfer != null) discard(transfer);
    }

    private void abort(Transfer transfer) {
        try {
            service.abort(transfer.session(), transfer.grant());
        } catch (RuntimeException exception) {
            System.err.println("File transfer cleanup failed (" + exception.getClass().getSimpleName() + ").");
        }
    }

    private static Message error(String code, String description, String correlationId, UUID transferId) {
        var payload = JsonNodeFactory.instance.objectNode().put("code", code).put("message", description);
        if (transferId != null) {
            payload.put("transferId", transferId.toString());
        }
        return new Message(MessageType.ERROR, correlationId, null, payload);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            var pending = List.copyOf(transfers.values());
            transfers.clear();
            pending.forEach(this::abort);
        }
    }
}
