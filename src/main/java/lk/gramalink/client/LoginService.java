package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.Message;
import lk.gramalink.shared.MessageType;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
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
    private volatile boolean closed;

    public LoginService(Consumer<NetworkClient.Status> status, Executor callbacks) {
        this.status = Objects.requireNonNull(status);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    public CompletableFuture<Message> login(Input input) {
        Objects.requireNonNull(input);
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Login service is closed."));
        return CompletableFuture.supplyAsync(() -> {
            if (closed) throw new CompletionException(new IOException("Login service is closed."));
            if (client != null) client.close();
            client = new NetworkClient(input.host(), input.port(), new NetworkClient.Listener() {
                public void onStatus(NetworkClient.Status value) { status.accept(value); }
            }, callbacks);
            try {
                client.connect();
                if (closed) { client.close(); throw new IOException("Login service is closed."); }
                // Provisional payload contract: Shadhir must confirm these field names when adding AuthService.
                var payload = JsonNodeFactory.instance.objectNode()
                        .put("username", input.username()).put("password", input.password());
                return client.request(new Message(MessageType.LOGIN, payload), Duration.ofSeconds(10));
            } catch (IOException error) { throw new CompletionException(error); }
        }, worker).thenCompose(reply -> reply);
    }

    public CompletableFuture<Void> disconnect() {
        if (closed) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> { if (client != null) client.close(); }, worker);
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
        worker.execute(() -> { if (client != null) client.close(); });
        worker.shutdown();
    }
}
