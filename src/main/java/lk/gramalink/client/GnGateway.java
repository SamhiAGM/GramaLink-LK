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
        default void onLoading(boolean loading) { }
        default void onFileProgress(FileTransferProgress.Snapshot snapshot) { }
        default void onRecovery(ClientReliability.State state) { }
        default void onUpdate(GnData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final Object authorizationLock = new Object();
    private final AtomicLong sessionEpoch = new AtomicLong();
    private volatile ClientFiles files;
    void bindFiles(ClientFiles files){this.files=files;}
    @Override public boolean supportsFiles(){return protocol.loadsBackend() && isGnAuthorized() && files!=null;}
    @Override public CompletableFuture<String> upload(String requestId,java.nio.file.Path path){
        if(!supportsFiles())return CompletableFuture.failedFuture(new SecurityException("Fresh file authorization is required."));
        return files.upload(requestId,path).thenApply(id->{refresh();return id;});
    }
    @Override public CompletableFuture<java.nio.file.Path> download(String requestId,String documentId,java.nio.file.Path destination){
        if(!supportsFiles())return CompletableFuture.failedFuture(new SecurityException("Fresh file authorization is required."));
        return files.download(requestId,documentId,destination);
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
    void transferProgress(FileTransferProgress.Snapshot snapshot){
        long epoch=sessionEpoch.get();publish(epoch,true,target->target.onFileProgress(snapshot));
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
        if (protocol.loadsBackend() && message.correlationId() == null && java.util.Set.of(MessageType.NEW_REQUEST,
                MessageType.REQUEST_UPDATED, MessageType.NOTIFICATION, MessageType.CHAT_MESSAGE).contains(message.type())) { refresh(); return; }
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
    @Override public boolean permits(GnData.ActionKind action, GnData.RequestRow row) {
        return isGnAuthorized() && protocol.permits(action, row)
                && (!protocol.loadsBackend() || action == GnData.ActionKind.CHAT
                    || (row != null && row.version() != null && Objects.equals(revisions.get(row.id()), row.version())));
    }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshDirty = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String,Long> revisions = new ConcurrentHashMap<>();
    @Override public void refresh() {
        if (!protocol.loadsBackend() || !isGnAuthorized()) return;
        refreshDirty.set(true); if (!refreshQueued.compareAndSet(false,true)) return;
        long epoch=sessionEpoch.get();
        publish(epoch,true,target->target.onLoading(true));
        task(() -> {
            try {
                int passes=0;
                do {
                    refreshDirty.set(false);
                    var queries=new BackendQueries(client, () -> { requireEpoch(epoch); requireGnOfficer(); });
                    var updates=queries.officer("GN",identity().orElseThrow().userId());
                    requireEpoch(epoch); requireGnOfficer();
                    for(Object update:updates) {
                        if(update instanceof GnData.Requests snapshot) { revisions.clear(); snapshot.rows().forEach(r -> revisions.put(r.id(),r.version())); }
                        publish(epoch,true,target -> target.onUpdate((GnData.Update)update));
                    }
                } while(++passes<2 && refreshDirty.get() && isGnAuthorized() && sessionEpoch.get()==epoch);
            } catch(IOException error){ publish(epoch,true,target -> target.onError(error.getMessage())); }
            finally { publish(epoch,true,target->target.onLoading(false)); refreshQueued.set(false); if(refreshDirty.get() && isGnAuthorized()) refresh(); }
            return null;
        });
    }

    public CompletableFuture<Message> perform(GnData.Action action) {
        Objects.requireNonNull(action);
        if (!isGnAuthorized()) return CompletableFuture.failedFuture(new SecurityException("GN Officer authorization is required."));
        if (!protocol.supports(action.kind())) return CompletableFuture.failedFuture(GnProtocol.pending());
        final long commandEpoch = sessionEpoch.get();
        return task(() -> {
            requireEpoch(commandEpoch);
            requireGnOfficer();
            if(protocol.loadsBackend() && action.kind()!=GnData.ActionKind.CHAT
                    && (action.expectedVersion()==null || !Objects.equals(revisions.get(action.requestId()),action.expectedVersion())))
                throw new IllegalArgumentException("Reload the current assigned request before deciding.");
            Message outbound = protocol.encode(action);
            // This is a permitted integration boundary, not a claim that these operations work today.
            MessageType expected = action.kind() == GnData.ActionKind.CHAT ? MessageType.CHAT_MESSAGE : MessageType.STATUS_UPDATE;
            if (outbound.type() != expected) throw new IllegalArgumentException("Unsupported officer wire operation.");
            requireGnOfficer();
            requireEpoch(commandEpoch);
            return client.request(outbound, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply).whenComplete((reply,error) -> { if(protocol.loadsBackend()) { revisions.remove(action.requestId()); refresh(); } });
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
