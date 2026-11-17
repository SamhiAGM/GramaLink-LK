package lk.gramalink.client;

import lk.gramalink.shared.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** Uses the login connection. Never opens another socket or constructs backend payloads. */
public final class CitizenGateway implements CitizenWorkspace, AutoCloseable {
    public interface Listener {
        default void onLoading(boolean loading) { }
        default void onRecovery(ClientReliability.State state) { }
        default void onUpdate(CitizenData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final Object authorizationLock = new Object();
    private final AtomicLong sessionEpoch = new AtomicLong();
    private volatile ClientFiles files;
    void bindFiles(ClientFiles files){this.files=files;}
    @Override public boolean supportsFiles(){return protocol.loadsBackend() && isCitizenAuthorized() && files!=null;}
    @Override public CompletableFuture<String> upload(String requestId,java.nio.file.Path path){
        if(!supportsFiles())return CompletableFuture.failedFuture(new SecurityException("Fresh file authorization is required."));
        return files.upload(requestId,path).thenApply(id->{refresh();return id;});
    }
    @Override public CompletableFuture<java.nio.file.Path> download(String requestId,String documentId,java.nio.file.Path destination){
        if(!supportsFiles())return CompletableFuture.failedFuture(new SecurityException("Fresh file authorization is required."));
        return files.download(requestId,documentId,destination);
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
        final long epoch;
        synchronized (authorizationLock) { epoch = sessionEpoch.incrementAndGet(); identity = null; }
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) {
            CitizenData.Identity verified = protocol.citizenAuthorization(reply).orElse(null);
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
    /** Receiver entry point; decode work stays off the UI thread. */
    void incoming(Message message) {
        if (!isCitizenAuthorized()) return;
        // NetworkClient already routes correlated replies to their command/query future.
        if (message.correlationId() != null) return;
        final long epoch = sessionEpoch.get();
        if (protocol.loadsBackend() && message.correlationId() == null && java.util.Set.of(
                MessageType.NEW_REQUEST, MessageType.REQUEST_UPDATED, MessageType.NOTIFICATION, MessageType.CHAT_MESSAGE).contains(message.type())) {
            refresh(); return; // Events are refresh hints; only authorized query results become UI state.
        }
        if (message.type() == MessageType.ACK) return; // Transport receipt is not persistence.
        if (message.type() == MessageType.ERROR) {
            publish(epoch, true, target -> target.onError(LoginService.describe(message))); return;
        }
        if (message.type() != MessageType.NEW_REQUEST && message.type() != MessageType.REQUEST_UPDATED
                && message.type() != MessageType.NOTIFICATION && message.type() != MessageType.CHAT_MESSAGE) return;
        try {
            worker.execute(() -> {
                if (!isCitizenAuthorized() || sessionEpoch.get() != epoch) return;
                try { if (!protocol.acceptsEvent(message)) return; protocol.decodeEvent(message).ifPresent(update -> publish(epoch, true, target -> target.onUpdate(update))); }
                catch (RuntimeException error) { publish(epoch, true, target -> target.onError("The server event could not be read.")); }
            });
        } catch (RejectedExecutionException ignored) { /* Closing revokes access. */ }
    }

    void transferProgress(FileTransferProgress.Snapshot snapshot) {
        long epoch = sessionEpoch.get();
        if (snapshot.state() == FileTransferProgress.State.INCOMPLETE || snapshot.state() == FileTransferProgress.State.FAILED) {
            publish(epoch, false, target -> target.onError(snapshot.detail()));
        }
        if (isCitizenAuthorized()) publish(epoch, true, target -> target.onUpdate(new CitizenData.TransferProgress(
                snapshot.confirmedBytes(), snapshot.totalBytes(), snapshot.detail(), snapshot.state() == FileTransferProgress.State.TRANSPORT_COMPLETE)));
    }

    public boolean isCitizenAuthorized() { return !closed && connected && identity != null; }
    public Optional<CitizenData.Identity> identity() {
        return isCitizenAuthorized() ? Optional.ofNullable(identity) : Optional.empty();
    }
    public void requireCitizen() {
        if (!isCitizenAuthorized()) throw new SecurityException("Server-confirmed Citizen authorization is required.");
    }
    public boolean supports(CitizenProtocol.Operation operation) { return isCitizenAuthorized() && protocol.supports(operation); }
    @Override public boolean usesRegisteredDivision() { return protocol.loadsBackend(); }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshDirty = new java.util.concurrent.atomic.AtomicBoolean();
    private final Map<String,Long> revisions = new ConcurrentHashMap<>();
    @Override public boolean supportsCorrections(){return protocol.loadsBackend() && isCitizenAuthorized();}
    @Override public CompletableFuture<Message> resubmit(String requestId,Long version,String title,String description) {
        if(!protocol.loadsBackend() || !isCitizenAuthorized()) return CompletableFuture.failedFuture(new SecurityException("Current Citizen authorization is required."));
        if(title==null || title.isBlank() || title.length()>160 || description==null || description.isBlank() || description.length()>5000)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Enter a correction title (1–160 characters) and description (1–5000)."));
        long epoch=sessionEpoch.get();
        return task(()->{
            requireEpoch(epoch);requireCitizen();
            if(version==null || !Objects.equals(revisions.get(requestId),version)) throw new IllegalArgumentException("Reload this request before resubmitting corrections.");
            Message command=BackendCommands.change(requestId,version,"RESUBMIT",null);
            command.payload().put("title",title.strip()).put("description",description.strip());
            return client.request(command,Duration.ofSeconds(10));
        }).thenCompose(f->f).whenComplete((reply,error)->{revisions.remove(requestId);refresh();});
    }
    @Override public void refresh() {
        if (!protocol.loadsBackend() || !isCitizenAuthorized()) return;
        refreshDirty.set(true);
        if (!refreshQueued.compareAndSet(false, true)) return;
        final long epoch = sessionEpoch.get();
        publish(epoch,true,target->target.onLoading(true));
        task(() -> {
            try {
                int passes=0;
                do {
                    refreshDirty.set(false);
                    BackendQueries queries = new BackendQueries(client, () -> { requireEpoch(epoch); requireCitizen(); });
                    for (CitizenData.Update update : queries.citizen(identity().orElseThrow().userId())) {
                        if(update instanceof CitizenData.Requests snapshot){ revisions.clear();snapshot.rows().forEach(r->revisions.put(r.id(),r.version())); }
                        publish(epoch, true, target -> target.onUpdate(update));
                    }
                } while (++passes<2 && refreshDirty.get() && isCitizenAuthorized() && sessionEpoch.get() == epoch);
            } catch (IOException error) { publish(epoch, true, target -> target.onError("Refresh failed: " + error.getMessage())); }
            finally { publish(epoch,true,target->target.onLoading(false)); refreshQueued.set(false); if (refreshDirty.get() && isCitizenAuthorized()) refresh(); }
            return null;
        });
    }

    public CompletableFuture<Message> createRequest(CitizenData.Draft draft) {
        Objects.requireNonNull(draft);
        // Attachment negotiation fields are not agreed; never silently omit a selected attachment.
        if (draft.attachment() != null && !supportsFiles()) return CompletableFuture.failedFuture(CitizenProtocol.pending());
        return request(CitizenProtocol.Operation.CREATE_REQUEST, () -> protocol.createRequest(draft), MessageType.CREATE_REQUEST)
                .thenCompose(reply -> {
                    if(draft.attachment()==null || reply.type()==MessageType.ERROR) return CompletableFuture.completedFuture(reply);
                    String id=reply.payload().path("data").path("id").asText();
                    if(id.isBlank())return CompletableFuture.failedFuture(new IOException("Creation response missing request ID; upload not attempted."));
                    return upload(id,draft.attachment().path()).thenApply(documentId->reply);
                });
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
        final long commandEpoch = sessionEpoch.get();
        return task(() -> {
            requireEpoch(commandEpoch);
            requireCitizen();
            Message message = encode.get();
            if (message.type() != expected) throw new IllegalArgumentException("Unexpected outbound message type.");
            requireCitizen();
            requireEpoch(commandEpoch);
            return client.request(message, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply).thenApply(reply -> { if (protocol.loadsBackend()) refresh(); return reply; });
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
        synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); identity = null; }
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
    private void requireEpoch(long epoch) {
        if (sessionEpoch.get() != epoch) throw new SecurityException("Authorization session changed; pending operations are not replayed.");
    }
    private void publish(Consumer<Listener> event) { publish(sessionEpoch.get(), false, event); }
    private void publish(long epoch, boolean requireAuthorization, Consumer<Listener> event) {
        Listener target = listener;
        callbacks.execute(() -> {
            if (!closed && listener == target && sessionEpoch.get() == epoch
                    && (!requireAuthorization || isCitizenAuthorized())) event.accept(target);
        });
    }
    @Override public void close() { synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); closed = true; identity = null; } listener = new Listener() { }; worker.shutdown(); }
}
