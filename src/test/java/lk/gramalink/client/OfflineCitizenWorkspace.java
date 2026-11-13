package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * UI-only fixture. No socket, login response, credential validation or persistence.
 * It is excluded from the application JAR and never supplied to CitizenDashboardView.
 */
final class OfflineCitizenWorkspace implements CitizenWorkspace, AutoCloseable {
    private final ExecutorService files = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "citizen-preview-files"); thread.setDaemon(true); return thread;
    });
    private CitizenGateway.Listener listener = new CitizenGateway.Listener() { };
    boolean available = true;
    boolean exerciseAcknowledgement;
    int submissions;
    int sends;
    CompletableFuture<Message> nextReply;
    CompletableFuture<CitizenData.Attachment> nextAttachment;

    // Grants permission to exercise an isolated view fixture, not a real authenticated session.
    public void requireCitizen() { if (!available) throw new SecurityException("Offline fixture closed"); }
    public boolean isCitizenAuthorized() { return available; }
    public Optional<CitizenData.Identity> identity() {
        return Optional.of(new CitizenData.Identity("PREVIEW-NOT-A-SESSION", "Offline preview user"));
    }
    public boolean supports(CitizenProtocol.Operation operation) { return available && exerciseAcknowledgement; }
    public void setListener(CitizenGateway.Listener listener) { this.listener = listener; }
    public CompletableFuture<Message> createRequest(CitizenData.Draft draft) { submissions++; return reply(); }
    public CompletableFuture<Message> sendMessage(String id, String text) {
        if (text == null || text.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a message."));
        sends++; return reply();
    }
    private CompletableFuture<Message> reply() {
        return nextReply != null ? nextReply : CompletableFuture.completedFuture(new Message(MessageType.ACK, JsonNodeFactory.instance.objectNode()));
    }
    public CompletableFuture<CitizenData.Attachment> inspectAttachment(Path path) {
        if (nextAttachment != null) return nextAttachment;
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IOException("Unreadable fixture file");
                return new CitizenData.Attachment(path.toAbsolutePath(), path.getFileName().toString(), Files.size(path));
            } catch (IOException error) { throw new CompletionException(error); }
        }, files);
    }
    public CompletableFuture<Void> disconnect() { available = false; return CompletableFuture.completedFuture(null); }
    void emit(CitizenData.Update update) { listener.onUpdate(update); }
    void disconnectEvent() { available = false; listener.onStatus(NetworkClient.Status.DISCONNECTED); }

    void sampleData() {
        Instant early = Instant.parse("2026-01-01T04:00:00Z"), later = early.plusSeconds(600);
        emit(new CitizenData.Requests(List.of(
                new CitizenData.RequestRow("PREVIEW-001", "Fictional service A", early, "Sample status A"),
                new CitizenData.RequestRow("PREVIEW-002", "Fictional service B", later, "Sample status B"))));
        emit(new CitizenData.RequestDetails(new CitizenData.Details("PREVIEW-001", "Fictional request for layout testing. This has not been submitted or stored.", List.of(
                new CitizenData.StatusEntry(later, "Sample status B", "Later sample event; no real processing occurred."),
                new CitizenData.StatusEntry(early, "Sample status A", "Earlier sample event; no real processing occurred.")), List.of(
                new CitizenData.ChatEntry(later, "Fictional officer", "This is a local preview message, not a server response.")))));
        emit(new CitizenData.Notifications(List.of(new CitizenData.Notification(later, "Local UI fixture notification. No server was contacted.", "PREVIEW-001"))));
    }
    @Override public void close() { available = false; files.shutdownNow(); }
}
