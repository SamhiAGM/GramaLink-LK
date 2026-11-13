package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

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
    private NetworkClient client; // owned by worker
    private volatile CitizenGateway citizen;
    private final CitizenProtocol citizenProtocol;
    private volatile GnGateway gn;
    private final GnProtocol gnProtocol;
    private volatile boolean closed;

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks) {
        this(status, callbacks, CitizenProtocol.pendingBackend());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol) {
        this(status, callbacks, citizenProtocol, GnProtocol.pendingBackend());
    }

    LoginService(Consumer<NetworkClient.Status> status, Executor callbacks, CitizenProtocol citizenProtocol, GnProtocol gnProtocol) {
        this.status = Objects.requireNonNull(status);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.citizenProtocol = Objects.requireNonNull(citizenProtocol);
        this.gnProtocol = Objects.requireNonNull(gnProtocol);
    }

    public CompletableFuture<Message> login(Input input) {
        Objects.requireNonNull(input);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Login service is closed."));
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IOException("Login service is closed."));
            if (citizen != null) citizen.close();
            if (gn != null) gn.close();
            if (client != null) client.close();
            AtomicReference<CitizenGateway> binding = new AtomicReference<>();
            AtomicReference<GnGateway> gnBinding = new AtomicReference<>();
            client = new NetworkClient(input.host(), input.port(), new NetworkClient.Listener() {
                public void onStatus(NetworkClient.Status value) {
                    CitizenGateway gateway = binding.get();
                    gateway.connectionStatus(value);
                    gnBinding.get().connectionStatus(value);
                    callbacks.execute(() -> { if (citizen == gateway && !closed) status.accept(value); });
                }
                public void onMessage(Message message) { binding.get().incoming(message); gnBinding.get().incoming(message); }
            }, Runnable::run);
            CitizenGateway gateway = new CitizenGateway(client, citizenProtocol, callbacks);
            binding.set(gateway);
            citizen = gateway;
            GnGateway officerGateway = new GnGateway(client, gnProtocol, callbacks);
            gnBinding.set(officerGateway);
            gn = officerGateway;
            try {
                client.connect();
                if (closed) { client.close(); throw new IOException("Login service is closed."); }
                // Provisional payload contract: Shadhir must confirm these field names when adding AuthService.
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("username", input.username()).put("password", input.password());
                return client.request(new Message(MessageType.LOGIN, payload), Duration.ofSeconds(10))
                        .thenApply(reply -> {
                            gateway.acceptLoginReply(reply);
                            officerGateway.acceptLoginReply(reply);
                            return reply;
                        });
            } catch (IOException error) { throw new CompletionException(error); }
        }, worker).thenCompose(reply -> reply);
    }

    public CompletableFuture<Void> disconnect() {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> { if (client != null) client.close(); }, worker);
    }

    /** Empty until the correlated server reply has been validated by the agreed Citizen auth contract. */
    public Optional<CitizenGateway> citizenGateway() {
        CitizenGateway gateway = citizen;
        return gateway != null && gateway.isCitizenAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    /** No GN role strings are guessed: the default backend adapter always denies access. */
    public Optional<GnGateway> gnGateway() {
        GnGateway gateway = gn;
        return gateway != null && gateway.isGnAuthorized() ? Optional.of(gateway) : Optional.empty();
    }

    public static String describe(Message reply) {
        if (reply.type() == MessageType.ERROR) {
            String code = reply.payload().path("code").asText("SERVER_ERROR");
            String detail = reply.payload().path("message").asText("The server rejected this request.");
            return code + ": " + detail;
        }
        // U01 deliberately has no authorized navigation, even if a different server sends LOGIN_OK.
        return "Server response: " + reply.type() + ". Dashboard access is not available in this client yet.";
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        worker.execute(() -> {
            if (citizen != null) citizen.close();
            if (gn != null) gn.close();
            if (client != null) client.close();
        });
        worker.shutdown();
    }
}
