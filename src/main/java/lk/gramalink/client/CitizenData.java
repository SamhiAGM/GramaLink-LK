package lk.gramalink.client;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Client view models, not a specification of JSON fields or server status values. */
public final class CitizenData {
    private CitizenData() { }
    public record Identity(String userId, String displayName) {
        public Identity {
            if (userId == null || userId.isBlank() || displayName == null || displayName.isBlank())
                throw new IllegalArgumentException("A verified citizen identity is required.");
        }
    }
    public record RequestRow(String id, String type, Instant submittedAt, String status) { }
    public record StatusEntry(Instant at, String status, String description) { }
    public record ChatEntry(Instant at, String sender, String text) { }
    public record Notification(Instant at, String text, String requestId) { }
    public record Details(String requestId, String description, List<StatusEntry> timeline, List<ChatEntry> messages) {
        public Details {
            timeline = timeline.stream().sorted(Comparator.comparing(StatusEntry::at)).toList();
            messages = messages.stream().sorted(Comparator.comparing(ChatEntry::at)).toList();
        }
    }
    public record Attachment(Path path, String fileName, long totalBytes) { }
    public record Draft(String type, String division, String description, Attachment attachment) {
        public Draft {
            type = required(type, "Choose or enter a request type.");
            division = required(division, "Enter your GN division.");
            description = required(description, "Describe your request.");
        }
        private static String required(String value, String error) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
            return value.strip();
        }
    }
    public sealed interface Update permits Requests, RequestDetails, Notifications, TransferProgress { }
    public record Requests(List<RequestRow> rows) implements Update {
        public Requests { rows = List.copyOf(rows); }
    }
    public record RequestDetails(Details details) implements Update { }
    public record Notifications(List<Notification> entries) implements Update {
        public Notifications {
            entries = entries.stream().sorted(Comparator.comparing(Notification::at).reversed()).toList();
        }
    }
    /** Only an integrated transfer adapter may supply progress; selected files start at zero. */
    public record TransferProgress(long receivedBytes, long totalBytes, String stage) implements Update {
        public TransferProgress {
            if (receivedBytes < 0 || totalBytes < 0 || receivedBytes > totalBytes)
                throw new IllegalArgumentException("Invalid transfer byte count.");
            Objects.requireNonNull(stage);
        }
        public double fraction() { return totalBytes == 0 ? 0 : (double) receivedBytes / totalBytes; }
    }
}
