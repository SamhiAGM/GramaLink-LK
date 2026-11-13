package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.FileChunk;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** UI-independent login transport. No credential verification or authenticated state is created here. */
public final class LoginService implements AutoCloseable {
    public record Input(String host, int port, String username, String password) {
        public Input {
            host = Objects.requireNonNull(host).strip();
            username = Objects.requireNonNull(username).strip();
            if (host.isEmpty() || host.chars().anyMatch(Character::isWhitespace))
                throw new IllegalArgumentException("Enter a server IP address or hostname without spaces.");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
            if (username.isEmpty()) throw new IllegalArgumentException("Enter your username.");
            if (password == null || password.isBlank()) throw new IllegalArgumentException("Enter your password.");
        }
        @Override public String toString() { return "Login input for " + host + ":" + port + " (credentials hidden)"; }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gramalink-login-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<NetworkClient.Status> status;
    private final Executor callbacks;
    private volatile NetworkClient client; // connection work is owned by worker; shutdown may close from an independent IO task
    private volatile CitizenGateway citizen;
    private final CitizenProtocol citizenProtocol;
    private volatile GnGateway gn;
    private final GnProtocol gnProtocol;
    private volatile DsGateway ds;
    private final DsProtocol dsProtocol;
    private volatile AdminGateway administrator;
    private final AdminProtocol adminProtocol;
    private volatile ClientReliability reliability;
    private volatile FileTransferProgress fileProgress;
    private final Consumer<ClientReliability.State> recoveryListener;
    private final ClientReliability.Policy reliabilityPolicy;
    private volatile boolean closed;

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks) {
        this(status, callbacks, CitizenProtocol.pendingBackend());
    }

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, Consumer<ClientReliability.State> recoveryListener) {
        this(status, callbacks, CitizenProtocol.pendingBackend(), GnProtocol.pendingBackend(), DsProtocol.pendingBackend(), AdminProtocol.pendingBackend(), recoveryListener, ClientReliability.Policy.defaults());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol) {
        this(status, callbacks, citizenProtocol, GnProtocol.pendingBackend());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol, GnProtocol gnProtocol) {
        this(status, callbacks, citizenProtocol, gnProtocol, DsProtocol.pendingBackend());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol, GnProtocol gnProtocol, DsProtocol dsProtocol) {
        this(status, callbacks, citizenProtocol, gnProtocol, dsProtocol, AdminProtocol.pendingBackend());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol, GnProtocol gnProtocol, DsProtocol dsProtocol, AdminProtocol adminProtocol) {
        this(status, callbacks, citizenProtocol, gnProtocol, dsProtocol, adminProtocol, ignored -> { }, ClientReliability.Policy.defaults());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol, GnProtocol gnProtocol,
                 DsProtocol dsProtocol, AdminProtocol adminProtocol, Consumer<ClientReliability.State> recoveryListener, ClientReliability.Policy reliabilityPolicy) {
        this.status = Objects.requireNonNull(status);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.citizenProtocol = Objects.requireNonNull(citizenProtocol);
        this.gnProtocol = Objects.requireNonNull(gnProtocol);
        this.dsProtocol = Objects.requireNonNull(dsProtocol);
        this.adminProtocol = Objects.requireNonNull(adminProtocol);
        this.recoveryListener = Objects.requireNonNull(recoveryListener);
        this.reliabilityPolicy = Objects.requireNonNull(reliabilityPolicy);
    }

    public CompletableFuture<Message> login(Input input) {
        Objects.requireNonNull(input);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Login service is closed."));
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IOException("Login service is closed."));
            if (reliability != null) reliability.close();
            if (citizen != null) citizen.close();
            if (gn != null) gn.close();
            if (ds != null) ds.close();
            if (administrator != null) administrator.close();
            if (client != null) client.close();
            AtomicReference<CitizenGateway> binding = new AtomicReference<>();
            AtomicReference<GnGateway> gnBinding = new AtomicReference<>();
            AtomicReference<DsGateway> dsBinding = new AtomicReference<>();
            AtomicReference<AdminGateway> adminBinding = new AtomicReference<>();
            AtomicReference<ClientReliability> recoveryBinding = new AtomicReference<>();
            AtomicReference<FileTransferProgress> transferBinding = new AtomicReference<>();
            AtomicReference<NetworkClient> transportBinding = new AtomicReference<>();
            client = new NetworkClient(input.host(), input.port(), new NetworkClient.Listener() {
                public void onStatus(NetworkClient.Status value) {
                    ClientReliability recovery = recoveryBinding.get();
                    if (value == NetworkClient.Status.DISCONNECTED) transferBinding.get().disconnected(recovery.generation());
                    CitizenGateway gateway = binding.get();
                    gateway.connectionStatus(value);
                    gnBinding.get().connectionStatus(value);
                    dsBinding.get().connectionStatus(value);
                    adminBinding.get().connectionStatus(value);
                    if (value == NetworkClient.Status.CONNECTED) recovery.connected(); else recovery.transportDisconnected();
                    long epoch = recovery.generation();
                    callbacks.execute(() -> { if (citizen == gateway && !closed && recovery.generation() == epoch) status.accept(value); });
                }
                public void onMessage(Message message) {
                    try { transferBinding.get().acceptControl(recoveryBinding.get().generation(), message); }
                    catch (IOException error) { binding.get().transportError("Invalid file transport response. Transfer stopped; storage is unconfirmed."); transportBinding.get().disconnectForRecovery(); return; }
                    binding.get().incoming(message); gnBinding.get().incoming(message); dsBinding.get().incoming(message);
                    adminBinding.get().incoming(message);
                }
                public void onChunk(FileChunk chunk) {
                    try { transferBinding.get().acceptDownloadChunk(recoveryBinding.get().generation(), chunk); }
                    catch (IOException error) { binding.get().transportError("Unnegotiated or invalid file chunk rejected. No file was stored."); transportBinding.get().disconnectForRecovery(); }
                }
                public void onDisconnectRequested() {
                    ClientReliability recovery = recoveryBinding.get();
                    if (recovery != null) { transferBinding.get().disconnected(recovery.generation()); recovery.close(); }
                }
            }, Runnable::run);
            transportBinding.set(client);
            CitizenGateway gateway = new CitizenGateway(client, citizenProtocol, callbacks);
            binding.set(gateway);
            citizen = gateway;
            GnGateway officerGateway = new GnGateway(client, gnProtocol, callbacks);
            gnBinding.set(officerGateway);
            gn = officerGateway;
            DsGateway dsGateway = new DsGateway(client, dsProtocol, callbacks);
            dsBinding.set(dsGateway); ds = dsGateway;
            AdminGateway adminGateway = new AdminGateway(client, adminProtocol, callbacks);
            adminBinding.set(adminGateway); administrator = adminGateway;
            NetworkClient transport = client;
            Runnable revoke = () -> {
                FileTransferProgress progress = transferBinding.get(); ClientReliability recovery = recoveryBinding.get();
                if (progress != null && recovery != null) progress.disconnected(recovery.generation());
                gateway.connectionStatus(NetworkClient.Status.DISCONNECTED); officerGateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                dsGateway.connectionStatus(NetworkClient.Status.DISCONNECTED); adminGateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
            };
            ClientReliability recovery = new ClientReliability(transport, reliabilityPolicy, state -> {
                ClientReliability source = recoveryBinding.get();
                if (source == null || source.state() != state || citizen != gateway || closed) return;
                gateway.recoveryState(state); officerGateway.recoveryState(state); dsGateway.recoveryState(state); adminGateway.recoveryState(state);
                long epoch = source.generation();
                callbacks.execute(() -> { if (citizen == gateway && !closed && source.generation() == epoch && source.state() == state) recoveryListener.accept(state); });
            }, revoke);
            recoveryBinding.set(recovery); reliability = recovery;
            FileTransferProgress progress = new FileTransferProgress(() -> citizen == gateway && !closed
                    && authorizedCount(gateway, officerGateway, dsGateway, adminGateway) == 1, recovery::generation, Runnable::run, gateway::transferProgress);
            transferBinding.set(progress); fileProgress = progress;
            try {
                transport.connect(reliabilityPolicy.connectTimeout());
                if (closed) { client.close(); throw new IOException("Login service is closed."); }
                // Provisional payload contract: Shadhir must confirm these field names when adding AuthService.
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("username", input.username()).put("password", input.password());
                return client.request(new Message(MessageType.LOGIN, payload), Duration.ofSeconds(10))
                        .thenApply(reply -> {
                            gateway.acceptLoginReply(reply);
                            officerGateway.acceptLoginReply(reply);
                            dsGateway.acceptLoginReply(reply);
                            adminGateway.acceptLoginReply(reply);
                            if (authorizedCount(gateway, officerGateway, dsGateway, adminGateway) == 1) recovery.authenticated();
                            return reply;
                        });
            } catch (IOException error) { recovery.connectionFailed(); throw new CompletionException(error); }
        }, worker).thenCompose(reply -> reply);
    }

    public CompletableFuture<Void> disconnect() {
        if (closed) return CompletableFuture.completedFuture(null);
        ClientReliability recovery = reliability; if (recovery != null) recovery.close();
        NetworkClient transport = client;
        // Do not queue a close behind a blocked file/socket write on the login worker.
        return CompletableFuture.runAsync(() -> { if (transport != null) transport.close(); });
    }

    public ClientReliability.State recoveryState() { ClientReliability current = reliability; return current == null ? ClientReliability.State.OFFLINE : current.state(); }
    /** Available only to a real authenticated backend file adapter. No metadata schema or upload is invented. */
    public Optional<FileTransferProgress> fileProgress() {
        return !closed && authorizedCount(citizen, gn, ds, administrator) == 1 ? Optional.ofNullable(fileProgress) : Optional.empty();
    }
    /** Bounded transport only; impossible before a validated READY grant. Does not open/read a local file. */
    public CompletableFuture<Void> sendFileChunk(FileChunk chunk) {
        Objects.requireNonNull(chunk);
        NetworkClient transport = client; ClientReliability recovery = reliability; FileTransferProgress progress = fileProgress;
        if (closed || recovery == null || recovery.state() != ClientReliability.State.ONLINE || fileProgress().isEmpty())
            return CompletableFuture.failedFuture(new SecurityException("Fresh authenticated file authorization is required."));
        long epoch = recovery.generation();
        try {
            return CompletableFuture.runAsync(() -> {
                if (client != transport || recovery.generation() != epoch || recovery.state() != ClientReliability.State.ONLINE || fileProgress().isEmpty())
                    throw new SecurityException("The authenticated file connection changed.");
                try {
                    progress.markUploadChunk(epoch, chunk);
                    transport.sendChunk(chunk);
                } catch (IOException error) {
                    progress.failTransfer(epoch, chunk.transferId(), "Chunk write failed. Storage is unconfirmed; a new authorized transfer is required.");
                    throw new CompletionException(error);
                }
            }, worker);
        } catch (RejectedExecutionException error) { return CompletableFuture.failedFuture(error); }
    }
    private static int authorizedCount(CitizenGateway citizen, GnGateway gn, DsGateway ds, AdminGateway admin) {
        return (citizen != null && citizen.isCitizenAuthorized() ? 1 : 0) + (gn != null && gn.isGnAuthorized() ? 1 : 0)
                + (ds != null && ds.isDsAuthorized() ? 1 : 0) + (admin != null && admin.isAdministratorAuthorized() ? 1 : 0);
    }

    /** Empty until the correlated server reply has been validated by the agreed Citizen auth contract. */
    public Optional<CitizenGateway> citizenGateway() {
        if (closed) return Optional.empty();
        CitizenGateway gateway = citizen;
        return gateway != null && gateway.isCitizenAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    /** No GN role strings are guessed: the default backend adapter always denies access. */
    public Optional<GnGateway> gnGateway() {
        if (closed) return Optional.empty();
        GnGateway gateway = gn;
        return gateway != null && gateway.isGnAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    /** Empty until the correlated login reply is validated by the agreed DS contract. */
    public Optional<DsGateway> dsGateway() {
        if (closed) return Optional.empty();
        DsGateway gateway = ds;
        return gateway != null && gateway.isDsAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    /** Empty until the matched server reply is validated by the agreed Administrator contract. */
    public Optional<AdminGateway> adminGateway() {
        if (closed) return Optional.empty();
        AdminGateway gateway = administrator;
        return gateway != null && gateway.isAdministratorAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    public static String describe(Message reply) {
        if (reply.type() == MessageType.ERROR) {
            String code = reply.payload().path("code").asText("SERVER_ERROR");
            String detail = reply.payload().path("message").asText("The server rejected this request.");
            return code + ": " + detail;
        }
        // A LOGIN_OK envelope alone is insufficient; guarded role adapters decide navigation.
        return "Server response: " + reply.type() + ". Dashboard access is not available in this client yet.";
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (reliability != null) reliability.close();
        if (citizen != null) citizen.close();
        if (gn != null) gn.close();
        if (ds != null) ds.close();
        if (administrator != null) administrator.close();
        NetworkClient transport = client;
        CompletableFuture.runAsync(() -> { if (transport != null) transport.close(); });
        worker.shutdown();
    }
}
