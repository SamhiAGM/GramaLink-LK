package lk.gramalink.client;

import java.time.Instant;
import java.util.*;

/** Administrator view models and local draft intents, not new server messages or payload schemas. */
public final class AdminData {
    private AdminData() { }
    public record Identity(String userId, String displayName) {
        public Identity { userId = required(userId, "Verified administrator identity required."); displayName = required(displayName, "Administrator name required."); }
    }
    public record Choice(String code, String label) {
        public Choice { code = required(code, "A catalogue code is required."); label = required(label, "A catalogue label is required."); }
        @Override public String toString() { return label; }
    }
    /** Session reference is a non-secret display reference, never a token/password. */
    public record ClientRow(String connectionId, String user, String role, String office, String remoteAddress,
                            String sessionReference, Instant connectedAt, Instant lastActivityAt) { }
    public record UserRow(String id, String username, String displayName, Choice role, String officeId, String state, boolean demoAccount) {
        public UserRow { id = required(id, "User ID required."); }
    }
    public record OfficeRow(String id, String name, Choice kind, String area, String state, boolean demoOffice) {
        public OfficeRow { id = required(id, "Office ID required."); }
        @Override public String toString() { return name == null ? id : name + " | " + id; }
    }
    public record StatusCount(String status, long count) {
        public StatusCount { status = required(status, "Backend status required."); if (count < 0) throw new IllegalArgumentException("Counts cannot be negative."); }
    }
    public record AuditEntry(Instant at, String user, String activity, String target, String outcome) { }
    public record UserDraft(String username, String displayName, Choice role, String officeId) {
        public UserDraft {
            username = required(username, "Enter a demo username."); displayName = required(displayName, "Enter a display name.");
            if (role == null) throw new IllegalArgumentException("Select a role supplied by the server catalogue.");
            officeId = officeId == null || officeId.isBlank() ? null : officeId.strip();
        }
    }
    public record OfficeDraft(String name, Choice kind, String area) {
        public OfficeDraft {
            name = required(name, "Enter an office name.");
            if (kind == null) throw new IllegalArgumentException("Select an office type supplied by the server catalogue.");
            area = required(area, "Enter the office area or division.");
        }
    }
    public enum Operation {
        CREATE_DEMO_USER("Create demo user"), UPDATE_DEMO_USER("Update selected demo user"), DISABLE_DEMO_USER("Disable selected demo user"),
        CREATE_DEMO_OFFICE("Create demo office"), UPDATE_DEMO_OFFICE("Update selected demo office"), ARCHIVE_DEMO_OFFICE("Archive selected demo office");
        private final String label;
        Operation(String label) { this.label = label; }
        @Override public String toString() { return label; }
        public boolean userOperation() { return ordinal() <= DISABLE_DEMO_USER.ordinal(); }
        public boolean create() { return this == CREATE_DEMO_USER || this == CREATE_DEMO_OFFICE; }
    }
    public record Command(Operation operation, String targetId, UserDraft user, OfficeDraft office, String reason) {
        public Command {
            Objects.requireNonNull(operation); reason = reason == null ? "" : reason.strip();
            if (!operation.create()) targetId = required(targetId, "Select a server-identified demo record first.");
            if (operation == Operation.CREATE_DEMO_USER || operation == Operation.UPDATE_DEMO_USER) Objects.requireNonNull(user, "Demo user fields required.");
            if (operation == Operation.CREATE_DEMO_OFFICE || operation == Operation.UPDATE_DEMO_OFFICE) Objects.requireNonNull(office, "Office fields required.");
            if (operation == Operation.DISABLE_DEMO_USER || operation == Operation.ARCHIVE_DEMO_OFFICE)
                reason = required(reason, "Enter a reason for disabling or archiving the demo record.");
        }
    }
    public sealed interface Update permits Overview, Clients, Users, Offices, Catalogues, Statistics, Audit { }
    /** Explicit backend totals. Null means unknown, not zero; table rows may be partial. */
    public record Overview(Long connectedClients, Long demoUsers, Long offices, Long requests, Instant asOf) implements Update {
        public Overview { nonnegative(connectedClients); nonnegative(demoUsers); nonnegative(offices); nonnegative(requests); }
    }
    public record Clients(List<ClientRow> rows) implements Update { public Clients { rows = List.copyOf(rows); } }
    public record Users(List<UserRow> rows) implements Update {
        public Users { rows = List.copyOf(rows); unique(rows.stream().map(UserRow::id).toList()); }
    }
    public record Offices(List<OfficeRow> rows) implements Update {
        public Offices { rows = List.copyOf(rows); unique(rows.stream().map(OfficeRow::id).toList()); }
    }
    public record Catalogues(List<Choice> roles, List<Choice> officeKinds) implements Update {
        public Catalogues {
            roles = List.copyOf(roles); officeKinds = List.copyOf(officeKinds);
            unique(roles.stream().map(Choice::code).toList()); unique(officeKinds.stream().map(Choice::code).toList());
        }
    }
    public record Statistics(List<StatusCount> rows, Long total, String scope, Instant asOf) implements Update {
        public Statistics { rows = List.copyOf(rows); unique(rows.stream().map(StatusCount::status).toList()); nonnegative(total); }
    }
    public record Audit(List<AuditEntry> rows) implements Update {
        public Audit { rows = rows.stream().sorted(Comparator.comparing(AuditEntry::at, Comparator.nullsLast(Comparator.reverseOrder()))).toList(); }
    }
    private static void nonnegative(Long value) { if (value != null && value < 0) throw new IllegalArgumentException("Counts cannot be negative."); }
    private static void unique(List<String> ids) { if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Duplicate entry in administrative snapshot."); }
    private static String required(String value, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error); return value.strip();
    }
}
