package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Test-only view fixture: no socket, LOGIN, authentication, storage or officer decisions. */
final class OfflineGnWorkspace implements GnWorkspace, AutoCloseable {
    private GnGateway.Listener listener = new GnGateway.Listener() { };
    boolean available = true;
    boolean exerciseActions;
    int operations;
    GnData.Action lastAction;
    CompletableFuture<Message> nextReply;
    // Isolated view-exercise permission, not a real GN-authenticated session.
    public void requireGnOfficer() { if (!available) throw new SecurityException("Offline fixture closed"); }
    public boolean isGnAuthorized() { return available; }
    public Optional<GnData.Identity> identity() { return Optional.of(new GnData.Identity("PREVIEW-NOT-A-GN-SESSION", "Fictional GN Officer (offline preview)")); }
    public boolean supports(GnData.ActionKind kind) { return available && exerciseActions; }
    public void setListener(GnGateway.Listener listener) { this.listener = listener; }
    public CompletableFuture<Message> perform(GnData.Action action) {
        operations++; lastAction = action;
        return nextReply != null ? nextReply : CompletableFuture.completedFuture(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
    }
    public CompletableFuture<Void> disconnect() { available = false; return CompletableFuture.completedFuture(null); }
    void emit(GnData.Update update) { listener.onUpdate(update); }
    void disconnected() { available = false; listener.onStatus(NetworkClient.Status.DISCONNECTED); }
    void sampleData() {
        Instant earlier = Instant.parse("2026-01-01T04:00:00Z"), later = earlier.plusSeconds(600);
        emit(new GnData.Requests(List.of(
                new GnData.RequestRow("PREVIEW-GN-001", "Fictional Citizen A", "Fictional service A", earlier, "Sample review status", null),
                new GnData.RequestRow("PREVIEW-GN-002", "Fictional Citizen B", "Fictional service B", later, "Sample waiting status", "Sample priority"))));
        emit(new GnData.RequestDetails(new GnData.Details("PREVIEW-GN-001", "Fictional request and document metadata for visual testing only. Nothing has been submitted or verified.",
                List.of(new GnData.Document("PREVIEW-DOC-001", "fictional-supporting-document.pdf", "Sample - not verified")),
                List.of(new GnData.History(later, "Sample review status", "Later fictional event, no real processing."),
                        new GnData.History(earlier, "Sample initial status", "Earlier fictional event, no real submission.")),
                List.of(new GnData.Chat(later, "Fictional Citizen A", "This is local sample chat, not a message from a real citizen.")))));
        emit(new GnData.Notifications(List.of(new GnData.Notification(later,
                "Fictional assignment notification. No officer service was contacted.", "PREVIEW-GN-001"))));
    }
    @Override public void close() { available = false; listener = new GnGateway.Listener() { }; }
}
