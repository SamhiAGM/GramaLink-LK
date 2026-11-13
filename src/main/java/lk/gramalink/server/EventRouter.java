package lk.gramalink.server;

import lk.gramalink.shared.JsonUtil;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side integration API for Shadhir's future backend services. The backend
 * must authorize every recipient and validate event payloads before invoking it.
 * Recipient IDs are explicit; this class never broadcasts or handles client input.
 */
public final class EventRouter {
    private static final Set<MessageType> EVENT_TYPES = Set.of(
            MessageType.NEW_REQUEST, MessageType.REQUEST_UPDATED,
            MessageType.CHAT_MESSAGE, MessageType.NOTIFICATION);

    private final SessionManager sessionManager;

    /** Use the same SessionManager instance supplied to NetworkServer. */
    public EventRouter(SessionManager sessionManager) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
    }

    /** Counts successful local writes, not client acknowledgements or persistence. */
    public record DeliveryResult(String userId, int deliveredConnections, int failedConnections) {
        public boolean offline() {
            return deliveredConnections == 0 && failedConnections == 0;
        }
    }

    /** The caller must supply a canonical user ID authorized by the backend. */
    public DeliveryResult sendToUser(String authorizedRecipientUserId, Message event) throws IOException {
        return sendToUsers(List.of(authorizedRecipientUserId), event).getFirst();
    }

    /**
     * Delivers to every current authenticated connection of each authorized user.
     * Duplicate IDs are sent once, preserving input order in the immutable results.
     * Offline users and connection failures do not stop delivery to other users.
     * Writes are synchronous; there is no offline queue, retry, or delivery ACK.
     * Invalid event data is rejected before any connection is written or closed.
     */
    public List<DeliveryResult> sendToUsers(Collection<String> authorizedRecipientUserIds, Message event)
            throws IOException {
        Objects.requireNonNull(authorizedRecipientUserIds, "Authorized recipient IDs are required.");
        var recipients = new LinkedHashSet<String>();
        for (String userId : authorizedRecipientUserIds) {
            Objects.requireNonNull(userId, "Recipient user ID is required.");
            if (userId.isBlank()) {
                throw new IllegalArgumentException("Recipient user ID must not be blank.");
            }
            recipients.add(userId);
        }

        Objects.requireNonNull(event, "Event is required.");
        if (!EVENT_TYPES.contains(event.type())) {
            throw new IllegalArgumentException("Unsupported server event type: " + event.type());
        }
        if (event.token() != null) {
            throw new IllegalArgumentException("Server events must not contain authentication tokens.");
        }
        // Keep one stable payload for the complete fan-out and preflight its size.
        Message snapshot = new Message(event.type(), event.correlationId(), null, event.payload().deepCopy());
        JsonUtil.toJson(snapshot);

        var results = new ArrayList<DeliveryResult>();
        for (String userId : recipients) {
            int delivered = 0;
            int failed = 0;
            for (SessionManager.Session session : sessionManager.findOnlineSessions(userId)) {
                try {
                    if (sessionManager.sendToSession(session, snapshot)) {
                        delivered++;
                    } else {
                        failed++;
                    }
                } catch (IOException exception) {
                    // SessionManager closes/removes only the failed connection.
                    failed++;
                }
            }
            results.add(new DeliveryResult(userId, delivered, failed));
        }
        return List.copyOf(results);
    }
}
