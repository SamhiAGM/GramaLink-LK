package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Test-only UI fixture: no NetworkClient, authentication, administrative service or storage. */
final class OfflineAdminWorkspace implements AdminWorkspace, AutoCloseable {
    static final AdminData.Choice ROLE = new AdminData.Choice("PREVIEW_ROLE_CITIZEN", "Fictional Citizen role");
    static final AdminData.Choice OFFICER_ROLE = new AdminData.Choice("PREVIEW_ROLE_GN", "Fictional GN role");
    static final AdminData.Choice GN = new AdminData.Choice("PREVIEW_KIND_GN", "GN office (fictional)");
    static final AdminData.Choice DS = new AdminData.Choice("PREVIEW_KIND_DS", "DS office (fictional)");
    private AdminGateway.Listener listener = new AdminGateway.Listener() { };
    boolean available = true, exerciseActions;
    int operations;
    AdminData.Command lastCommand;
    CompletableFuture<Message> nextReply;
    // Permits exercising an isolated test view; does not authenticate a real administrator.
    public void requireAdministrator() { if (!available) throw new SecurityException("Offline fixture closed"); }
    public boolean isAdministratorAuthorized() { return available; }
    public Optional<AdminData.Identity> identity() { return Optional.of(new AdminData.Identity("PREVIEW-NOT-AN-ADMIN-SESSION", "Fictional Administrator (offline preview)")); }
    public boolean supports(AdminData.Operation operation) { return available && exerciseActions; }
    public void setListener(AdminGateway.Listener listener) { this.listener = listener; }
    public CompletableFuture<Message> perform(AdminData.Command command) {
        operations++; lastCommand = command;
        return nextReply != null ? nextReply : CompletableFuture.completedFuture(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
    }
    public CompletableFuture<Void> disconnect() { available = false; return CompletableFuture.completedFuture(null); }
    void emit(AdminData.Update update) { listener.onUpdate(update); }
    void disconnected() { available = false; listener.onStatus(NetworkClient.Status.DISCONNECTED); }
    static AdminData.Offices offices() {
        return new AdminData.Offices(List.of(
                new AdminData.OfficeRow("PREVIEW-GN-DEMO", "Fictional GN demo office", GN, "Fictional division A", "Sample state", true),
                new AdminData.OfficeRow("PREVIEW-DS-DEMO", "Fictional DS demo office", DS, "Fictional division B", "Sample state", true),
                new AdminData.OfficeRow("PREVIEW-OFFICE-READONLY", "Fictional read-only office", GN, "Fictional area C", "Sample state", false)));
    }
    static AdminData.Users users() {
        return new AdminData.Users(List.of(
                new AdminData.UserRow("PREVIEW-USER-001", "fictional.citizen", "Fictional Citizen", ROLE, null, "Sample account state", true),
                new AdminData.UserRow("PREVIEW-USER-002", "fictional.officer", "Fictional GN Officer", OFFICER_ROLE, "PREVIEW-GN-DEMO", "Sample account state", true),
                new AdminData.UserRow("PREVIEW-USER-READONLY", "fictional.readonly", "Fictional read-only account", ROLE, null, "Sample account state", false)));
    }
    void sampleData() {
        Instant early = Instant.parse("2026-01-01T04:00:00Z"), later = early.plusSeconds(600);
        emit(new AdminData.Catalogues(List.of(ROLE, OFFICER_ROLE), List.of(GN, DS))); emit(offices()); emit(users());
        emit(new AdminData.Clients(List.of(
                new AdminData.ClientRow("PREVIEW-CONNECTION-001", "Fictional Citizen", "Fictional role", null, "192.0.2.10 (fictional)", "PREVIEW-PUBLIC-REF-1", early, later),
                new AdminData.ClientRow("PREVIEW-CONNECTION-002", "Fictional Officer", "Fictional role", "Fictional GN office", null, null, early, null))));
        emit(new AdminData.Statistics(List.of(new AdminData.StatusCount("Fictional state A", 17), new AdminData.StatusCount("Fictional state B", 9),
                new AdminData.StatusCount("Fictional state C", 4)), 40L, "Fictional partial status sample; totals are not table-derived", later));
        emit(new AdminData.Audit(List.of(
                new AdminData.AuditEntry(early, "Fictional administrator", "Fictional account activity - not executed", "PREVIEW-USER-001", "Fictional record"),
                new AdminData.AuditEntry(later, "Fictional service", "Fictional office activity - not executed", "PREVIEW-GN-DEMO", "Fictional record"),
                new AdminData.AuditEntry(null, "Fictional unknown timestamp", "Fictional audit sample", "PREVIEW-ONLY", "Fictional record"))));
        // Explicit fictional totals intentionally differ from partial table row counts.
        emit(new AdminData.Overview(12L, 8L, 4L, 40L, later));
    }
    @Override public void close() { available = false; listener = new AdminGateway.Listener() { }; }
}
