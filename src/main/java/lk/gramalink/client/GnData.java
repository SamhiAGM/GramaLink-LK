package lk.gramalink.client;

import java.time.Instant;
import java.util.*;

/** Local GN view models and action intents. These are not JSON schemas or server status codes. */
public final class GnData {
    private GnData() { }
    public record Identity(String userId, String displayName) {
        public Identity {
            userId = required(userId, "A verified officer ID is required.");
            displayName = required(displayName, "A verified officer name is required.");
        }
    }
    public record RequestRow(String id, String citizen, String type, Instant submittedAt, String status, String priority) { }
    public record Document(String id, String fileName, String verificationStatus) { }
    public record History(Instant at, String status, String description) { }
    public record Chat(Instant at, String sender, String text) { }
    public record Notification(Instant at, String text, String requestId) { }
    public record Details(String requestId, String description, List<Document> documents, List<History> history, List<Chat> messages) {
        public Details {
            requestId = required(requestId, "A request ID is required.");
            documents = List.copyOf(documents);
            history = history.stream().sorted(Comparator.comparing(History::at)).toList();
            messages = messages.stream().sorted(Comparator.comparing(Chat::at)).toList();
        }
    }
    public enum ActionKind {
        VERIFY_DOCUMENT("Verify document"), REQUEST_INFORMATION("Request more information"),
        FORWARD_TO_DS("Forward to DS"), REJECT_REQUEST("Reject request"), CHAT("Send message");
        private final String label;
        ActionKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Action(ActionKind kind, String requestId, String documentId, String note) {
        public Action {
            Objects.requireNonNull(kind);
            requestId = required(requestId, "Select an assigned request first.");
            note = note == null ? "" : note.strip();
            if (kind == ActionKind.VERIFY_DOCUMENT) documentId = required(documentId, "Select a supporting document first.");
            if (kind == ActionKind.REJECT_REQUEST) note = required(note, "Enter a rejection reason.");
            if (kind == ActionKind.REQUEST_INFORMATION) note = required(note, "Describe the additional information required.");
            if (kind == ActionKind.CHAT) note = required(note, "Enter a message for this request.");
        }
    }
    public sealed interface Update permits Requests, RequestDetails, Notifications { }
    /** Complete current assignment snapshot, not an invented paged wire response. */
    public record Requests(List<RequestRow> rows) implements Update {
        public Requests { rows = List.copyOf(rows); }
    }
    public record RequestDetails(Details details) implements Update {
        public RequestDetails { Objects.requireNonNull(details); }
    }
    public record Notifications(List<Notification> entries) implements Update {
        public Notifications { entries = entries.stream().sorted(Comparator.comparing(Notification::at).reversed()).toList(); }
    }
    private static String required(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
        return value.strip();
    }
}
