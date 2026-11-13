package lk.gramalink.client;

import lk.gramalink.shared.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Uses the login connection. Never opens another socket or constructs backend payloads. */
public final class CitizenGateway implements CitizenWorkspace, AutoCloseable {
    public interface Listener {
        default void onUpdate(CitizenData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final NetworkClient client;
    private final CitizenProtocol protocol;
    private final Executor callbacks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gramalink-citizen-worker");
        thread.setDaemon(true);
        return thread;
    });
    private volatile CitizenData.Identity identity;
    private volatile Listener listener = new Listener() { };
    private volatile boolean connected;
    private volatile boolean closed;

    CitizenGateway(NetworkClient client, CitizenProtocol protocol, Executor callbacks) {
        this.client = Objects.requireNonNull(client);
        this.protocol = Objects.requireNonNull(protocol);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    // Called only by LoginService on its correlation-matched LOGIN future, never from unsolicited events.
    void acceptLoginReply(Message reply) {
        identity = null;
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) {
            identity = protocol.citizenAuthorization(reply).orElse(null);
        }
    }

    void connectionStatus(NetworkClient.Status status) {
        connected = status == NetworkClient.Status.CONNECTED;
        if (!connected) identity = null;
        publish(target -> target.onStatus(status));
    }

    /** Receiver entry point; decode work stays off the UI thread. */
    void incoming(Message message) {
        if (closed || !isCitizenAuthorized()) return;
        if (message.type() != MessageType.NEW_REQUEST && message.type() != MessageType.REQUEST_UPDATED
                && message.type() != MessageType.NOTIFICATION && message.type() != MessageType.CHAT_MESSAGE) return;
        try {
            worker.execute(() -> {
                if (!isCitizenAuthorized()) return;
                try { protocol.decodeEvent(message).ifPresent(update -> publish(target -> target.onUpdate(update))); }
                catch (RuntimeException error) { publish(target -> target.onError("The server event could not be read.")); }
            });
        } catch (RejectedExecutionException ignored) { /* Closing revokes access. */ }
    }

    public boolean isCitizenAuthorized() { return !closed && connected && identity != null; }
    public Optional<CitizenData.Identity> identity() {
        return isCitizenAuthorized() ? Optional.ofNullable(identity) : Optional.empty();
    }
    public void requireCitizen() {
        if (!isCitizenAuthorized()) throw new SecurityException("Server-confirmed Citizen authorization is required.");
    }
    public boolean supports(CitizenProtocol.Operation operation) { return isCitizenAuthorized() && protocol.supports(operation); }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }

    public CompletableFuture<Message> createRequest(CitizenData.Draft draft) {
        Objects.requireNonNull(draft);
        // Attachment negotiation fields are not agreed; never silently omit a selected attachment.
        if (draft.attachment() != null) return CompletableFuture.failedFuture(CitizenProtocol.pending());
        return request(CitizenProtocol.Operation.CREATE_REQUEST, () -> protocol.createRequest(draft), MessageType.CREATE_REQUEST);
    }
    public CompletableFuture<Message> sendMessage(String requestId, String text) {
        if (requestId == null || requestId.isBlank() || text == null || text.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Select a request and enter a message."));
        return request(CitizenProtocol.Operation.CHAT_MESSAGE,
                () -> protocol.chatMessage(requestId, text.strip()), MessageType.CHAT_MESSAGE);
    }
    private CompletableFuture<Message> request(CitizenProtocol.Operation operation,
                                               java.util.function.Supplier<Message> encode, MessageType expected) {
        if (!isCitizenAuthorized()) return CompletableFuture.failedFuture(new SecurityException("Citizen authorization is required."));
        if (!protocol.supports(operation)) return CompletableFuture.failedFuture(CitizenProtocol.pending());
        return task(() -> {
            requireCitizen();
            Message message = encode.get();
            if (message.type() != expected) throw new IllegalArgumentException("Unexpected outbound message type.");
            requireCitizen();
            return client.request(message, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply);
    }
    public CompletableFuture<CitizenData.Attachment> inspectAttachment(Path path) {
        return task(() -> {
            requireCitizen();
            if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
                throw new IOException("Choose a readable file.");
            return new CitizenData.Attachment(path.toAbsolutePath(), path.getFileName().toString(), Files.size(path));
        });
    }
    public CompletableFuture<Void> disconnect() {
        identity = null;
        return task(() -> { client.disconnect(); return null; });
    }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> task(Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Citizen connection is closed."));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return work.run(); } catch (IOException error) { throw new CompletionException(error); }
            }, worker);
        } catch (RejectedExecutionException error) { return CompletableFuture.failedFuture(error); }
    }
    private void publish(Consumer<Listener> event) {
        Listener target = listener;
        callbacks.execute(() -> { if (!closed && listener == target) event.accept(target); });
    }
    @Override public void close() { closed = true; identity = null; listener = new Listener() { }; worker.shutdown(); }
}
