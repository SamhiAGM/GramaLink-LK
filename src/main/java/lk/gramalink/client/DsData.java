package lk.gramalink.client;

import java.time.Instant;
import java.util.*;

/** DS view models/intents only; none of these record names define a wire schema. */
public final class DsData {
    private DsData() { }
    public record Identity(String userId, String displayName) {
        public Identity { userId = required(userId, "A verified DS identity is required."); displayName = required(displayName, "A verified DS name is required."); }
    }
    /** Preserve opaque backend revision values exactly; never generate, increment or numerically order them. */
    public record Version(String value) {
        public Version { if (value == null || value.isBlank()) throw new IllegalArgumentException("A nonblank server version is required."); }
    }
    public record RequestRow(String id, String citizen, String gnOffice, String type, String status, Instant submittedAt, Version version) {
        public RequestRow { id = required(id, "A request ID is required."); }
    }
    public record Document(String id, String fileName, String state) { }
    public record History(Instant at, String status, String description) { }
    public record Chat(Instant at, String sender, String text) { }
    public record Notification(Instant at, String text, String requestId) { }
    public record Details(String requestId, Version version, String description, String originatingGnOfficer,
                          List<Document> documents, List<History> history, List<Chat> messages) {
        public Details {
            requestId = required(requestId, "A request ID is required."); documents = List.copyOf(documents);
            history = history.stream().sorted(Comparator.comparing(History::at)).toList();
            messages = messages.stream().sorted(Comparator.comparing(Chat::at)).toList();
        }
    }
    public enum ActionKind {
        APPROVE("Approve request"), REJECT("Reject request"), RETURN_TO_GN("Return to GN"), CHAT("Send officer message");
        private final String label;
        ActionKind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Action(ActionKind kind, String requestId, Version expectedVersion, String note) {
        public Action {
            Objects.requireNonNull(kind); requestId = required(requestId, "Select a forwarded request first.");
            note = note == null ? "" : note.strip();
            if (kind == ActionKind.REJECT) note = required(note, "Enter a rejection reason.");
            if (kind == ActionKind.RETURN_TO_GN) note = required(note, "Explain why this request must return to GN.");
            if (kind == ActionKind.CHAT) note = required(note, "Enter an officer message.");
        }
    }
    public sealed interface Update permits Requests, RequestDetails, Notifications { }
    /** Complete DS-authorized inbox snapshot. A future adapter owns paging/event aggregation. */
    public record Requests(List<RequestRow> rows) implements Update {
        public Requests {
            rows = List.copyOf(rows);
            Set<String> ids = new HashSet<>();
            for (RequestRow row : rows) if (!ids.add(row.id())) throw new IllegalArgumentException("Duplicate request in DS snapshot.");
        }
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
