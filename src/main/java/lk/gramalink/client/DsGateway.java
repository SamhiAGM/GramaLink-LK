package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** DS operations reuse the authenticated login socket; the server owns every final permission/concurrency decision. */
public final class DsGateway implements DsWorkspace, AutoCloseable {
    public interface Listener {
        default void onUpdate(DsData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final NetworkClient client;
    private final DsProtocol protocol;
    private final Executor callbacks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gramalink-ds-worker"); thread.setDaemon(true); return thread;
    });
    private final Object stateLock = new Object();
    private final Map<String, DsData.RequestRow> requests = new HashMap<>();
    private final Set<String> refreshRequired = new HashSet<>();
    private volatile DsData.Identity identity;
    private volatile boolean connected, closed;
    private volatile Listener listener = new Listener() { };

    DsGateway(NetworkClient client, DsProtocol protocol, Executor callbacks) {
        this.client = Objects.requireNonNull(client); this.protocol = Objects.requireNonNull(protocol); this.callbacks = Objects.requireNonNull(callbacks);
    }
    // Only the correlation-matched LOGIN future calls this, never unsolicited LOGIN_OK events.
    void acceptLoginReply(Message reply) {
        identity = null;
        synchronized (stateLock) { requests.clear(); refreshRequired.clear(); }
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) identity = protocol.dsAuthorization(reply).orElse(null);
    }
    void connectionStatus(NetworkClient.Status status) {
        connected = status == NetworkClient.Status.CONNECTED;
        if (!connected) { identity = null; synchronized (stateLock) { requests.clear(); refreshRequired.clear(); } }
        publish(target -> target.onStatus(status));
    }
    void incoming(Message message) {
        if (!isDsAuthorized()) return;
        if (message.type() != MessageType.REQUEST_UPDATED && message.type() != MessageType.NEW_REQUEST
                && message.type() != MessageType.NOTIFICATION && message.type() != MessageType.CHAT_MESSAGE) return;
        task(() -> {
            if (!isDsAuthorized()) return null;
            try {
                protocol.decodeEvent(message).ifPresent(update -> {
                    if (!isDsAuthorized()) return;
                    if (update instanceof DsData.Requests snapshot) synchronized (stateLock) {
                        requests.clear(); snapshot.rows().forEach(row -> requests.put(row.id(), row));
                        refreshRequired.clear();
                    }
                    publish(target -> { if (isDsAuthorized()) target.onUpdate(update); });
                });
            } catch (RuntimeException error) { publish(target -> target.onError("The DS update could not be read. No local decision was applied.")); }
            return null;
        });
    }
    public boolean isDsAuthorized() { return !closed && connected && identity != null; }
    public void requireDsOfficer() {
        if (!isDsAuthorized()) throw new SecurityException("Server-confirmed DS Officer authorization is required.");
    }
    public Optional<DsData.Identity> identity() { return isDsAuthorized() ? Optional.ofNullable(identity) : Optional.empty(); }
    public boolean supports(DsData.ActionKind kind) { return isDsAuthorized() && protocol.supports(kind); }
    public boolean requiresVersion(DsData.ActionKind kind) { return protocol.requiresVersion(kind); }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }
    public CompletableFuture<Message> perform(DsData.Action action) {
        Objects.requireNonNull(action);
        if (!isDsAuthorized()) return CompletableFuture.failedFuture(new SecurityException("DS Officer authorization is required."));
        if (!protocol.supports(action.kind())) return CompletableFuture.failedFuture(DsProtocol.pending());
        return task(() -> {
            validateCurrent(action);
            Message outbound = protocol.encode(action);
            MessageType expected = action.kind() == DsData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE;
            if (outbound.type() != expected) throw new IllegalArgumentException("Unsupported DS wire operation.");
            validateCurrent(action);
            return client.request(outbound, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply).thenCompose(reply -> task(() -> {
            if (reply.type() == MessageType.ERROR) {
                String code = reply.payload().path("code").asText();
                if (code.equals("CONFLICT") || code.equals("FORBIDDEN")) synchronized (stateLock) { refreshRequired.add(action.requestId()); }
            }
            return reply;
        }));
    }
    private void validateCurrent(DsData.Action action) {
        requireDsOfficer();
        synchronized (stateLock) {
            DsData.RequestRow current = requests.get(action.requestId());
            if (current == null) throw new SecurityException("This request has not been confirmed in the current DS inbox.");
            if (refreshRequired.contains(action.requestId())) throw new IllegalArgumentException("Reload authoritative request data after CONFLICT or FORBIDDEN before retrying.");
            if (protocol.requiresVersion(action.kind())) {
                if (action.expectedVersion() == null || current.version() == null)
                    throw new IllegalArgumentException("The current server request version is required. Reload request data.");
                if (!current.version().equals(action.expectedVersion()))
                    throw new IllegalArgumentException("The selected request version is stale. Reload authoritative data before retrying.");
            }
        }
    }
    public CompletableFuture<Void> disconnect() {
        identity = null; synchronized (stateLock) { requests.clear(); refreshRequired.clear(); }
        return task(() -> { client.disconnect(); return null; });
    }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> task(Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("DS workspace is closed."));
        try { return CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (IOException error) { throw new CompletionException(error); }
        }, worker); } catch (RejectedExecutionException error) { return CompletableFuture.failedFuture(error); }
    }
    private void publish(Consumer<Listener> event) {
        Listener target = listener; callbacks.execute(() -> { if (!closed && listener == target) event.accept(target); });
    }
    @Override public void close() {
        closed = true; identity = null; listener = new Listener() { };
        synchronized (stateLock) { requests.clear(); refreshRequired.clear(); } worker.shutdown();
    }
}
