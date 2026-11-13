package lk.gramalink.server;

import lk.gramalink.shared.FileChunk;
import lk.gramalink.shared.Message;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.io.InputStream;

/**
 * Backend integration boundary, not an authorization or storage implementation.
 * Shadhir's service must validate permissions, metadata, total size, and destination
 * for the supplied verified session before issuing a fresh transfer ID/grant.
 */
public interface FileTransferService {
    enum Direction { UPLOAD, DOWNLOAD }

    record Grant(UUID transferId, Direction direction, long totalBytes) {
        public Grant {
            Objects.requireNonNull(transferId, "Transfer ID is required.");
            Objects.requireNonNull(direction, "Transfer direction is required.");
            if (transferId.getMostSignificantBits() == 0 && transferId.getLeastSignificantBits() == 0) {
                throw new IllegalArgumentException("Nil transfer ID is not allowed.");
            }
            if (totalBytes < 0) {
                throw new IllegalArgumentException("Total bytes must not be negative.");
            }
        }
    }

    Grant authorize(SessionManager.Session session, Message metadata) throws IOException;

    /** Consume only this bounded chunk; a return does not by itself prove durable storage. */
    default void receiveChunk(SessionManager.Session session, Grant grant, FileChunk chunk) throws IOException {
        throw new UnavailableException();
    }

    /** Release resources on protocol failure or disconnect; must be safe to call once per grant. */
    default void abort(SessionManager.Session session, Grant grant) {
    }

    /** Optional durable-completion response; legacy transport-only services return empty. */
    default Optional<Message> completion(SessionManager.Session session, UUID transferId) throws IOException {
        return Optional.empty();
    }

    /** Opens an already-authorized backend download. The handler closes the returned stream. */
    default InputStream download(SessionManager.Session session, Grant grant) throws IOException {
        throw new UnavailableException();
    }

    static FileTransferService unavailable() {
        return (session, metadata) -> { throw new UnavailableException(); };
    }

    final class UnavailableException extends IOException {
        public UnavailableException() {
            super("File authorization and storage integration is not available.");
        }
    }
}
