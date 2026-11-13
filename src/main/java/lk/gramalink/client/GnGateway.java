package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** GN operations on the same NetworkClient used to log in. No role is inferred locally. */
public final class GnGateway implements GnWorkspace, AutoCloseable {
    public interface Listener {
        default void onUpdate(GnData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final NetworkClient client;
    private final GnProtocol protocol;
    private final Executor callbacks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gramalink-gn-worker"); thread.setDaemon(true); return thread;
    });
    private volatile GnData.Identity identity;
    private volatile boolean connected, closed;
    private volatile Listener listener = new Listener() { };

    GnGateway(NetworkClient client, GnProtocol protocol, Executor callbacks) {
        this.client = Objects.requireNonNull(client);
        this.protocol = Objects.requireNonNull(protocol);
        this.callbacks = Objects.requireNonNull(callbacks);
    }
    // Only LoginService's correlation-matched LOGIN future calls this method.
    void acceptLoginReply(Message reply) {
        identity = null;
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK)
            identity = protocol.gnAuthorization(reply).orElse(null);
    }
    void connectionStatus(NetworkClient.Status status) {
        connected = status == NetworkClient.Status.CONNECTED;
        if (!connected) identity = null;
        publish(target -> target.onStatus(status));
    }
    void incoming(Message message) {
        if (!isGnAuthorized()) return;
        if (message.type() != MessageType.NEW_REQUEST && message.type() != MessageType.REQUEST_UPDATED
                && message.type() != MessageType.CHAT_MESSAGE && message.type() != MessageType.NOTIFICATION) return;
        task(() -> {
            if (!isGnAuthorized()) return null;
            try { protocol.decodeEvent(message).ifPresent(update -> publish(target -> { if (isGnAuthorized()) target.onUpdate(update); })); }
            catch (RuntimeException error) { publish(target -> target.onError("The officer update could not be read. No local status change was applied.")); }
            return null;
        });
    }
    public boolean isGnAuthorized() { return !closed && connected && identity != null; }
    public void requireGnOfficer() {
        if (!isGnAuthorized()) throw new SecurityException("Server-confirmed GN Officer authorization is required.");
    }
    public Optional<GnData.Identity> identity() { return isGnAuthorized() ? Optional.ofNullable(identity) : Optional.empty(); }
    public boolean supports(GnData.ActionKind action) { return isGnAuthorized() && protocol.supports(action); }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }
    public CompletableFuture<Message> perform(GnData.Action action) {
        Objects.requireNonNull(action);
        if (!isGnAuthorized()) return CompletableFuture.failedFuture(new SecurityException("GN Officer authorization is required."));
        if (!protocol.supports(action.kind())) return CompletableFuture.failedFuture(GnProtocol.pending());
        return task(() -> {
            requireGnOfficer();
            Message outbound = protocol.encode(action);
            // This is a permitted integration boundary, not a claim that these operations work today.
            MessageType expected = action.kind() == GnData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE;
            if (outbound.type() != expected) throw new IllegalArgumentException("Unsupported officer wire operation.");
            requireGnOfficer();
            return client.request(outbound, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply);
    }
    public CompletableFuture<Void> disconnect() {
        identity = null;
        return task(() -> { client.disconnect(); return null; });
    }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> task(Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Officer workspace is closed."));
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
