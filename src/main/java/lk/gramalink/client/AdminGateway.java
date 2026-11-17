package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/** Administrative boundary on the login NetworkClient. Server authorization is always final. */
public final class AdminGateway implements AdminWorkspace, AutoCloseable {
    public interface Listener {
        default void onRecovery(ClientReliability.State state) { }
        default void onUpdate(AdminData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
    private final Object authorizationLock = new Object();
    private final AtomicLong sessionEpoch = new AtomicLong();
    private final NetworkClient client;
    private final AdminProtocol protocol;
    private final Executor callbacks;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gramalink-admin-worker"); thread.setDaemon(true); return thread;
    });
    private final Object stateLock = new Object();
    private final Map<String, AdminData.UserRow> users = new HashMap<>();
    private final Map<String, AdminData.OfficeRow> offices = new HashMap<>();
    private AdminData.Catalogues catalogues = new AdminData.Catalogues(List.of(), List.of());
    private volatile AdminData.Identity identity;
    private volatile boolean connected, closed;
    private volatile Listener listener = new Listener() { };
    private static final Set<MessageType> NON_ADMIN_COMMANDS = Set.of(MessageType.LOGIN, MessageType.LOGIN_OK,
            MessageType.ACK, MessageType.ERROR, MessageType.HEARTBEAT, MessageType.FILE_META, MessageType.FILE_CHUNK);

    AdminGateway(NetworkClient client, AdminProtocol protocol, Executor callbacks) {
        this.client = Objects.requireNonNull(client); this.protocol = Objects.requireNonNull(protocol); this.callbacks = Objects.requireNonNull(callbacks);
    }
    // LoginService calls this only from the correlation-matched LOGIN future.
    void acceptLoginReply(Message reply) {
        final long epoch;
        synchronized (authorizationLock) { epoch = sessionEpoch.incrementAndGet(); identity = null; }
        clearState();
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) {
            AdminData.Identity verified = protocol.administratorAuthorization(reply).orElse(null);
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
        if (!connected) clearState();
        publish(target -> target.onStatus(status));
    }
    void transportError(String message) { publish(target -> target.onError(message)); }
    void recoveryState(ClientReliability.State state) {
        if (state != ClientReliability.State.ONLINE) {
            synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); identity = null; }
            clearState();
        }
        publish(target -> target.onRecovery(state));
    }
    void incoming(Message message) {
        if (!isAdministratorAuthorized()) return;
        final long epoch = sessionEpoch.get();
        if (message.type() == MessageType.ACK) return; // Transport receipt is not persistence.
        if (message.type() == MessageType.ERROR) {
            publish(epoch, true, target -> target.onError(LoginService.describe(message))); return;
        }
        if (message.type() == MessageType.LOGIN_OK || !protocol.eventTypes().contains(message.type())) return;
        task(() -> {
            if (!isAdministratorAuthorized() || sessionEpoch.get() != epoch) return null;
            try {
                if (!protocol.acceptsEvent(message)) return null;
                protocol.decodeEvent(message).ifPresent(update -> {
                    synchronized (authorizationLock) {
                        if (!isAdministratorAuthorized() || sessionEpoch.get() != epoch) return;
                    synchronized (stateLock) {
                        if (update instanceof AdminData.Users snapshot) { users.clear(); snapshot.rows().forEach(row -> users.put(row.id(), row)); }
                        else if (update instanceof AdminData.Offices snapshot) { offices.clear(); snapshot.rows().forEach(row -> offices.put(row.id(), row)); }
                        else if (update instanceof AdminData.Catalogues snapshot) catalogues = snapshot;
                    }
                    }
                    publish(epoch, true, target -> target.onUpdate(update));
                });
            } catch (RuntimeException error) { publish(epoch, true, target -> target.onError("The administrative update could not be read. No local record or statistic was changed.")); }
            return null;
        });
    }
    public boolean isAdministratorAuthorized() { return !closed && connected && identity != null; }
    public void requireAdministrator() {
        if (!isAdministratorAuthorized()) throw new SecurityException("Server-confirmed Administrator authorization is required.");
    }
    public Optional<AdminData.Identity> identity() { return isAdministratorAuthorized() ? Optional.ofNullable(identity) : Optional.empty(); }
    public boolean supports(AdminData.Operation operation) {
        return isAdministratorAuthorized() && protocol.supports(operation) && !protocol.outboundTypes(operation).isEmpty();
    }
    public void setListener(Listener listener) { this.listener = Objects.requireNonNull(listener); }
    public CompletableFuture<Message> perform(AdminData.Command command) {
        Objects.requireNonNull(command);
        if (!isAdministratorAuthorized()) return CompletableFuture.failedFuture(new SecurityException("Administrator authorization is required."));
        if (!supports(command.operation())) return CompletableFuture.failedFuture(AdminProtocol.pending());
        final long commandEpoch = sessionEpoch.get();
        return task(() -> {
            requireEpoch(commandEpoch);
            validate(command);
            Message outbound = protocol.encode(command);
            if (NON_ADMIN_COMMANDS.contains(outbound.type()) || !protocol.outboundTypes(command.operation()).contains(outbound.type()))
                throw new IllegalArgumentException("The administrative wire operation is not agreed for this action.");
            validate(command);
            requireEpoch(commandEpoch);
            return client.request(outbound, Duration.ofSeconds(10));
        }).thenCompose(reply -> reply);
    }
    private void validate(AdminData.Command command) {
        requireAdministrator();
        synchronized (stateLock) {
            if (!command.operation().create()) {
                if (command.operation().userOperation()) {
                    AdminData.UserRow row = users.get(command.targetId());
                    if (row == null || !row.demoAccount()) throw new SecurityException("Only server-identified demo accounts may be managed.");
                } else {
                    AdminData.OfficeRow row = offices.get(command.targetId());
                    if (row == null || !row.demoOffice()) throw new SecurityException("Only server-identified demo offices may be managed.");
                }
            }
            if (command.user() != null) {
                if (catalogues.roles().stream().noneMatch(role -> role.equals(command.user().role()))) throw new IllegalArgumentException("Reload the backend role catalogue before submitting.");
                if (command.user().officeId() != null && !offices.containsKey(command.user().officeId())) throw new IllegalArgumentException("Select an office supplied by the server.");
            }
            if (command.office() != null && catalogues.officeKinds().stream().noneMatch(kind -> kind.equals(command.office().kind())))
                throw new IllegalArgumentException("Reload the backend office-type catalogue before submitting.");
        }
    }
    public CompletableFuture<Void> disconnect() { synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); identity = null; } clearState(); return task(() -> { client.disconnect(); return null; }); }
    private void clearState() { synchronized (stateLock) { users.clear(); offices.clear(); catalogues = new AdminData.Catalogues(List.of(), List.of()); } }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> task(Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Administrator workspace is closed."));
        try { return CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (IOException error) { throw new CompletionException(error); }
        }, worker); } catch (RejectedExecutionException error) { return CompletableFuture.failedFuture(error); }
    }
    private void requireEpoch(long epoch) {
        if (sessionEpoch.get() != epoch) throw new SecurityException("Authorization session changed; pending operations are not replayed.");
    }
    private void publish(Consumer<Listener> event) { publish(sessionEpoch.get(), false, event); }
    private void publish(long epoch, boolean requireAuthorization, Consumer<Listener> event) {
        Listener target = listener;
        callbacks.execute(() -> {
            if (!closed && listener == target && sessionEpoch.get() == epoch
                    && (!requireAuthorization || isAdministratorAuthorized())) event.accept(target);
        });
    }
    @Override public void close() { synchronized (authorizationLock) { sessionEpoch.incrementAndGet(); closed = true; identity = null; } listener = new Listener() { }; clearState(); worker.shutdown(); }
}
