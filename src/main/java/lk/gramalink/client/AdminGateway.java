package lk.gramalink.client;

import lk.gramalink.shared.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Administrative boundary on the login NetworkClient. Server authorization is always final. */
public final class AdminGateway implements AdminWorkspace, AutoCloseable {
    public interface Listener {
        default void onUpdate(AdminData.Update update) { }
        default void onStatus(NetworkClient.Status status) { }
        default void onError(String message) { }
    }
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
        identity = null; clearState();
        if (!closed && connected && reply.type() == MessageType.LOGIN_OK) identity = protocol.administratorAuthorization(reply).orElse(null);
    }
    void connectionStatus(NetworkClient.Status status) {
        connected = status == NetworkClient.Status.CONNECTED;
        if (!connected) { identity = null; clearState(); }
        publish(target -> target.onStatus(status));
    }
    void incoming(Message message) {
        if (!isAdministratorAuthorized() || message.type() == MessageType.LOGIN_OK || !protocol.eventTypes().contains(message.type())) return;
        task(() -> {
            if (!isAdministratorAuthorized()) return null;
            try {
                protocol.decodeEvent(message).ifPresent(update -> {
                    if (!isAdministratorAuthorized()) return;
                    synchronized (stateLock) {
                        if (update instanceof AdminData.Users snapshot) { users.clear(); snapshot.rows().forEach(row -> users.put(row.id(), row)); }
                        else if (update instanceof AdminData.Offices snapshot) { offices.clear(); snapshot.rows().forEach(row -> offices.put(row.id(), row)); }
                        else if (update instanceof AdminData.Catalogues snapshot) catalogues = snapshot;
                    }
                    publish(target -> { if (isAdministratorAuthorized()) target.onUpdate(update); });
                });
            } catch (RuntimeException error) { publish(target -> target.onError("The administrative update could not be read. No local record or statistic was changed.")); }
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
        return task(() -> {
            validate(command);
            Message outbound = protocol.encode(command);
            if (NON_ADMIN_COMMANDS.contains(outbound.type()) || !protocol.outboundTypes(command.operation()).contains(outbound.type()))
                throw new IllegalArgumentException("The administrative wire operation is not agreed for this action.");
            validate(command);
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
    public CompletableFuture<Void> disconnect() { identity = null; clearState(); return task(() -> { client.disconnect(); return null; }); }
    private void clearState() { synchronized (stateLock) { users.clear(); offices.clear(); catalogues = new AdminData.Catalogues(List.of(), List.of()); } }
    @FunctionalInterface private interface Work<T> { T run() throws IOException; }
    private <T> CompletableFuture<T> task(Work<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Administrator workspace is closed."));
        try { return CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (IOException error) { throw new CompletionException(error); }
        }, worker); } catch (RejectedExecutionException error) { return CompletableFuture.failedFuture(error); }
    }
    private void publish(Consumer<Listener> event) {
        Listener target = listener; callbacks.execute(() -> { if (!closed && listener == target) event.accept(target); });
    }
    @Override public void close() { closed = true; identity = null; listener = new Listener() { }; clearState(); worker.shutdown(); }
}
