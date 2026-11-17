package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;
import lk.gramalink.shared.FileChunk;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** UI-independent login transport; only a correlated backend identity authorizes a workspace. */
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
    private final Object sessionLock = new Object();
    private final AtomicLong loginGeneration = new AtomicLong();
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
    private volatile ClientFiles files;
    private final Consumer<ClientReliability.State> recoveryListener;
    private final ClientReliability.Policy reliabilityPolicy;
    private volatile boolean closed;

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks) {
        this(status, callbacks, CitizenProtocol.productionBackend(), GnProtocol.productionBackend(),
                DsProtocol.productionBackend(), AdminProtocol.productionBackend(),
                ignored -> { }, ClientReliability.Policy.defaults());
    }

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, Consumer<ClientReliability.State> recoveryListener) {
        this(status, callbacks, CitizenProtocol.productionBackend(), GnProtocol.productionBackend(),
                DsProtocol.productionBackend(), AdminProtocol.productionBackend(),
                recoveryListener, ClientReliability.Policy.defaults());
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
        final long attempt;
        synchronized (sessionLock) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Login service is closed."));
            attempt = loginGeneration.incrementAndGet();
            retireCurrentSessionLocked();
        }
        return CompletableFuture.supplyAsync(() -> {
            if (!isCurrentAttempt(attempt, null))
                throw new CompletionException(new SecurityException("This login attempt was replaced."));
            AtomicReference<CitizenGateway> binding = new AtomicReference<>();
            AtomicReference<GnGateway> gnBinding = new AtomicReference<>();
            AtomicReference<DsGateway> dsBinding = new AtomicReference<>();
            AtomicReference<AdminGateway> adminBinding = new AtomicReference<>();
            AtomicReference<ClientReliability> recoveryBinding = new AtomicReference<>();
            AtomicReference<FileTransferProgress> transferBinding = new AtomicReference<>();
            AtomicReference<ClientFiles> filesBinding = new AtomicReference<>();
            AtomicReference<NetworkClient> transportBinding = new AtomicReference<>();
            NetworkClient transport = new NetworkClient(input.host(), input.port(), new NetworkClient.Listener() {
                public void onStatus(NetworkClient.Status value) {
                    if (!isCurrentAttempt(attempt, transportBinding.get())) return;
                    ClientReliability recovery = recoveryBinding.get();
                    if (value == NetworkClient.Status.DISCONNECTED) { transferBinding.get().disconnected(recovery.generation()); if(filesBinding.get()!=null) filesBinding.get().disconnected(); }
                    CitizenGateway gateway = binding.get();
                    gateway.connectionStatus(value);
                    gnBinding.get().connectionStatus(value);
                    dsBinding.get().connectionStatus(value);
                    adminBinding.get().connectionStatus(value);
                    if (value == NetworkClient.Status.CONNECTED) recovery.connected(); else recovery.transportDisconnected();
                    long epoch = recovery.generation();
                    callbacks.execute(() -> {
                        if (isCurrentAttempt(attempt, transportBinding.get()) && citizen == gateway
                                && recovery.generation() == epoch) status.accept(value);
                    });
                }
                public void onMessage(Message message) {
                    if (!isCurrentAttempt(attempt, transportBinding.get())) return;
                    try { transferBinding.get().acceptControl(recoveryBinding.get().generation(), message); filesBinding.get().control(message); }
                    catch (IOException error) { binding.get().transportError("Invalid file transport response. Transfer stopped; storage is unconfirmed."); transportBinding.get().disconnectForRecovery(); return; }
                    if(message.type()==MessageType.ERROR && "UNAUTHENTICATED".equals(message.payload().path("code").asText())
                            && authorizedCount(binding.get(),gnBinding.get(),dsBinding.get(),adminBinding.get())==1) {
                        transportBinding.get().disconnectForRecovery(); return;
                    }
                    binding.get().incoming(message); gnBinding.get().incoming(message); dsBinding.get().incoming(message);
                    adminBinding.get().incoming(message);
                }
                public void onChunk(FileChunk chunk) {
                    if (!isCurrentAttempt(attempt, transportBinding.get())) return;
                    try { transferBinding.get().acceptDownloadChunk(recoveryBinding.get().generation(), chunk); filesBinding.get().chunk(chunk); }
                    catch (IOException error) { binding.get().transportError("Unnegotiated or invalid file chunk rejected. No file was stored."); transportBinding.get().disconnectForRecovery(); }
                }
                public void onDisconnectRequested() {
                    ClientReliability recovery = recoveryBinding.get();
                    if (recovery != null && isCurrentAttempt(attempt, transportBinding.get())) {
                        transferBinding.get().disconnected(recovery.generation());
                        recovery.close();
                    }
                }
            }, Runnable::run);
            transportBinding.set(transport);
            CitizenGateway gateway = new CitizenGateway(transport, citizenProtocol, callbacks);
            GnGateway officerGateway = new GnGateway(transport, gnProtocol, callbacks);
            DsGateway dsGateway = new DsGateway(transport, dsProtocol, callbacks);
            AdminGateway adminGateway = new AdminGateway(transport, adminProtocol, callbacks);
            Runnable revoke = () -> {
                FileTransferProgress progress = transferBinding.get(); ClientReliability recovery = recoveryBinding.get();
                if (progress != null && recovery != null) progress.disconnected(recovery.generation());
                gateway.connectionStatus(NetworkClient.Status.DISCONNECTED); officerGateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
                dsGateway.connectionStatus(NetworkClient.Status.DISCONNECTED); adminGateway.connectionStatus(NetworkClient.Status.DISCONNECTED);
            };
            ClientReliability recovery = new ClientReliability(transport, reliabilityPolicy, state -> {
                ClientReliability source = recoveryBinding.get();
                if (source == null || source.state() != state || !isCurrentAttempt(attempt, transport)
                        || citizen != gateway) return;
                gateway.recoveryState(state); officerGateway.recoveryState(state); dsGateway.recoveryState(state); adminGateway.recoveryState(state);
                long epoch = source.generation();
                callbacks.execute(() -> {
                    if (isCurrentAttempt(attempt, transport) && citizen == gateway
                            && source.generation() == epoch && source.state() == state) recoveryListener.accept(state);
                });
            }, revoke);
            recoveryBinding.set(recovery);
            FileTransferProgress progress = new FileTransferProgress(() -> citizen == gateway && !closed
                    && authorizedCount(gateway, officerGateway, dsGateway, adminGateway) == 1, recovery::generation, Runnable::run, snapshot -> { gateway.transferProgress(snapshot); officerGateway.transferProgress(snapshot); dsGateway.transferProgress(snapshot); });
            transferBinding.set(progress);
            ClientFiles fileClient=new ClientFiles(transport,progress,recovery::generation,()->
                    !closed && isCurrentAttempt(attempt,transport) && authorizedCount(gateway,officerGateway,dsGateway,adminGateway)==1);
            filesBinding.set(fileClient); gateway.bindFiles(fileClient); officerGateway.bindFiles(fileClient); dsGateway.bindFiles(fileClient);

            synchronized (sessionLock) {
                if (!isCurrentAttempt(attempt, null)) {
                    gateway.close(); officerGateway.close(); dsGateway.close(); adminGateway.close();
                    recovery.close(); fileClient.close();
                    transport.close();
                    throw new CompletionException(new SecurityException("This login attempt was replaced."));
                }
                client = transport;
                reliability = recovery; fileProgress = progress; files = fileClient;
                binding.set(gateway); citizen = gateway;
                gnBinding.set(officerGateway); gn = officerGateway;
                dsBinding.set(dsGateway); ds = dsGateway;
                adminBinding.set(adminGateway); administrator = adminGateway;
            }
            try {
                transport.connect(reliabilityPolicy.connectTimeout());
                if (!isCurrentAttempt(attempt, transport)) {
                    transport.close();
                    throw new SecurityException("This login attempt was replaced.");
                }
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("username", input.username()).put("password", input.password());
                String correlationId = UUID.randomUUID().toString();
                return transport.request(new Message(MessageType.LOGIN, correlationId, null, payload), Duration.ofSeconds(10))
                        .thenApply(reply -> completeLogin(attempt, correlationId, reply, transport, recovery,
                                gateway, officerGateway, dsGateway, adminGateway));
            } catch (IOException error) {
                if (isCurrentAttempt(attempt, transport)) recovery.connectionFailed();
                throw new CompletionException(error);
            }
        }, worker).thenCompose(reply -> reply);
    }

    public CompletableFuture<Void> disconnect() {
        long signedOutGeneration;
        synchronized (sessionLock) {
            if (closed) return CompletableFuture.completedFuture(null);
            signedOutGeneration = loginGeneration.incrementAndGet();
            retireCurrentSessionLocked();
        }
        callbacks.execute(() -> {
            if (!closed && loginGeneration.get() == signedOutGeneration && client == null)
                status.accept(NetworkClient.Status.DISCONNECTED);
        });
        return CompletableFuture.completedFuture(null);
    }

    private Message completeLogin(long attempt, String correlationId, Message reply, NetworkClient transport,
                                 ClientReliability recovery, CitizenGateway gateway, GnGateway officerGateway,
                                 DsGateway dsGateway, AdminGateway adminGateway) {
        if (!isCurrentAttempt(attempt, transport))
            throw new CompletionException(new SecurityException("This login response belongs to a replaced session."));
        if (!correlationId.equals(reply.correlationId()))
            throw rejectLogin(attempt, transport, "The login response correlation did not match the request.");
        if (reply.type() == MessageType.ERROR) return reply;
        if (reply.type() != MessageType.LOGIN_OK)
            throw rejectLogin(attempt, transport, "The server returned an invalid login response.");

        gateway.acceptLoginReply(reply);
        officerGateway.acceptLoginReply(reply);
        dsGateway.acceptLoginReply(reply);
        adminGateway.acceptLoginReply(reply);
        int authorized = authorizedCount(gateway, officerGateway, dsGateway, adminGateway);
        if (authorized != 1 || !isCurrentAttempt(attempt, transport))
            throw rejectLogin(attempt, transport, "The server returned an invalid or unsupported login identity.");

        recovery.authenticated();
        return reply;
    }

    private SecurityException rejectLogin(long attempt, NetworkClient transport, String message) {
        long revokedGeneration = -1;
        synchronized (sessionLock) {
            if (isCurrentAttempt(attempt, transport)) {
                revokedGeneration = loginGeneration.incrementAndGet();
                retireCurrentSessionLocked();
            }
        }
        if (revokedGeneration >= 0) {
            long signedOutGeneration = revokedGeneration;
            callbacks.execute(() -> {
                if (!closed && loginGeneration.get() == signedOutGeneration && client == null)
                    status.accept(NetworkClient.Status.DISCONNECTED);
            });
        }
        return new SecurityException(message);
    }

    private boolean isCurrentAttempt(long attempt, NetworkClient expectedClient) {
        return !closed && loginGeneration.get() == attempt
                && (expectedClient == null || client == expectedClient);
    }

    private void retireCurrentSessionLocked() {
        ClientFiles oldFiles=files; files=null;
        ClientReliability oldReliability = reliability;
        CitizenGateway oldCitizen = citizen;
        GnGateway oldGn = gn;
        DsGateway oldDs = ds;
        AdminGateway oldAdmin = administrator;
        NetworkClient oldClient = client;
        reliability = null; fileProgress = null;
        citizen = null; gn = null; ds = null; administrator = null; client = null;
        if(oldFiles!=null) oldFiles.close();
        if (oldCitizen != null) oldCitizen.close();
        if (oldGn != null) oldGn.close();
        if (oldDs != null) oldDs.close();
        if (oldAdmin != null) oldAdmin.close();
        if (oldReliability != null) oldReliability.close();
        if (oldClient != null) oldClient.close();
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
        synchronized (sessionLock) {
            if (closed) return;
            closed = true;
            loginGeneration.incrementAndGet();
            retireCurrentSessionLocked();
        }
        worker.shutdown();
    }
}
