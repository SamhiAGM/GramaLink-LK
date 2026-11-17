package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Test-only DS view fixture. No login, socket, real permission, decision, upload or persistence. */
final class OfflineDsWorkspace implements DsWorkspace, AutoCloseable {
    static final DsData.Version REVISION = new DsData.Version("FICTIONAL-REV-A");
    private DsGateway.Listener listener = new DsGateway.Listener() { };
    boolean available = true, exerciseActions;
    int operations;
    DsData.Action lastAction;
    CompletableFuture<Message> nextReply;
    // Allows exercising an isolated view fixture; does not authenticate a DS officer.
    public void requireDsOfficer() { if (!available) throw new SecurityException("Offline fixture closed"); }
    public boolean isDsAuthorized() { return available; }
    public Optional<DsData.Identity> identity() { return Optional.of(new DsData.Identity("PREVIEW-NOT-A-DS-SESSION", "Fictional DS Officer (offline preview)")); }
    public boolean supports(DsData.ActionKind kind) { return available && exerciseActions; }
    public boolean requiresVersion(DsData.ActionKind kind) { return kind != DsData.ActionKind.CHAT; }
    public void setListener(DsGateway.Listener listener) { this.listener = listener; }
    public CompletableFuture<Message> perform(DsData.Action action) {
        operations++; lastAction = action;
        return nextReply != null ? nextReply : CompletableFuture.completedFuture(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
    }
    public CompletableFuture<Void> disconnect() { available = false; return CompletableFuture.completedFuture(null); }
    void emit(DsData.Update update) { listener.onUpdate(update); }
    void disconnected() { available = false; listener.onStatus(NetworkClient.Status.DISCONNECTED); }
    static DsData.RequestRow first() {
        return new DsData.RequestRow("PREVIEW-DS-001", "Fictional Citizen A", "Fictional GN Office A", "Fictional service A", "Sample review state", Instant.parse("2026-01-01T04:00:00Z"), REVISION);
    }
    void sampleData() {
        Instant early = Instant.parse("2026-01-01T04:00:00Z"), later = early.plusSeconds(600);
        emit(new DsData.Requests(List.of(first(), new DsData.RequestRow("PREVIEW-DS-002", "Fictional Citizen B", "Fictional GN Office B", "Fictional service B", "Sample waiting state", null, null))));
        emit(new DsData.RequestDetails(new DsData.Details("PREVIEW-DS-001", REVISION,
                "Fictional forwarded request for UI testing only. No approval, rejection or return has occurred.", "Fictional GN Officer A",
                List.of(new DsData.Document("PREVIEW-DOC-001", "fictional-forwarded-document.pdf", "Fictional metadata only")),
                List.of(new DsData.History(later, "Sample review state", "Later fictional event. No real decision."),
                        new DsData.History(early, "Sample initial state", "Earlier fictional event. No real submission.")),
                List.of(new DsData.Chat(later, "Fictional GN Officer A", "Local sample officer chat; no message was sent.")))));
        emit(new DsData.RequestDetails(new DsData.Details("PREVIEW-DS-002", null, "Fictional request without supplied date or revision.",
                "Fictional GN Officer B", List.of(), List.of(), List.of())));
        emit(new DsData.Notifications(List.of(new DsData.Notification(later, "Fictional DS notification. No server has been contacted.", "PREVIEW-DS-001"))));
    }
    @Override public void close() { available = false; listener = new DsGateway.Listener() { }; }
}
