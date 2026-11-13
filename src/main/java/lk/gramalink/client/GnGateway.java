package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** GN operations on the same NetworkClient used to log in. No role is inferred locally. */
public final class GnGateway implements GnWorkspace, AutoCloseable {
    public interface Listener {
        default void onRecovery(ClientReliability.State state) { }
        default void onUpdate(GnData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final Object authorizationLock = new Object();
    private final AtomicLong sessionEpoch = new AtomicLong();
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
        final long epoch;
        synchronized (authorizationLock) { epoch = sessionEpoch.incrementAndGet(); identity = null; }
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) {
            GnData.Identity verified = protocol.gnAuthorization(reply).orElse(null);
            synchronized (authorizationLock) {
                if (!closed && connected && sessionEpoch.get() == epoch) identity = verified;
            }
        }
    }
    void connectionStatus(NetworkClient.Status status) {
        synchronized (authorizationLock) {
            sessionEpoch.incrementAndGet();
            connected = status == NetworkClient.Status.CONNECTED;
            identity = null; // A new TCP connection never inherits role authorization.
        }
        publish(target -> target.onStatus(status));
    }
    void transportError(String message) { publish(target -> target.onError(message)); }
    void recoveryState(ClientReliability.State state) {
        if (state != ClientReliability.State.ONLINE) {
            synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); identity = null; }
        }
        publish(target -> target.onRecovery(state));
    }
    void incoming(Message message) {
        if (!isGnAuthorized()) return;
        final long epoch = sessionEpoch.get();
        if (message.type() == MessageType.ACK) return; // Transport receipt is not persistence.
        if (message.type() == MessageType.ERROR) {
            publish(epoch, true, target -> target.onError(LoginService.describe(message))); return;
        }
        if (message.type() != MessageType.NEW_REQUEST && message.type() != MessageType.REQUEST_UPDATED
                && message.type() != MessageType.CHAT_MESSAGE && message.type() != MessageType.NOTIFICATION) return;
        task(() -> {
            if (!isGnAuthorized() || sessionEpoch.get() != epoch) return null;
            try { if (!protocol.acceptsEvent(message)) return null; protocol.decodeEvent(message).ifPresent(update -> publish(epoch, true, target -> target.onUpdate(update))); }
            catch (RuntimeException error) { publish(epoch, true, target -> target.onError("The officer update could not be read. No local status change was applied.")); }
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
        final long commandEpoch = sessionEpoch.get();
        return task(() -> {
            requireEpoch(commandEpoch);
            requireGnOfficer();
            Message outbound = protocol.encode(action);
            // This is a permitted integration boundary, not a claim that these operations work today.
            MessageType expected = action.kind() == GnData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE;
            if (outbound.type() != expected) throw new IllegalArgumentException("Unsupported officer wire operation.");
            requireGnOfficer();
            requireEpoch(commandEpoch);
            return client.request(outbound, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply);
    }
    public CompletableFuture<Void> disconnect() {
        synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); identity = null; }
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
    private void requireEpoch(long epoch) {
        if (sessionEpoch.get() != epoch) throw new SecurityException("Authorization session changed; pending operations are not replayed.");
    }
    private void publish(Consumer<Listener> event) { publish(sessionEpoch.get(), false, event); }
    private void publish(long epoch, boolean requireAuthorization, Consumer<Listener> event) {
        Listener target = listener;
        callbacks.execute(() -> {
            if (!closed && listener == target && sessionEpoch.get() == epoch
                    && (!requireAuthorization || isGnAuthorized())) event.accept(target);
        });
    }
    @Override public void close() { synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); closed = true; identity = null; } listener = new Listener() { }; worker.shutdown(); }
}
