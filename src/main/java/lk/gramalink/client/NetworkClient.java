package lk.gramalink.client;

import lk.gramalink.shared.*;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * UI-independent TCP client. Connect/send may block: call them off the FX thread.
 * The default callback executor runs inline; JavaFX can supply Platform::runLater.
 * Callbacks must return promptly. Correlated replies complete futures on the receiver
 * thread, so UI continuations must also dispatch onto the FX thread.
 */
public final class NetworkClient implements AutoCloseable {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 5000;
    public enum Status { CONNECTED, DISCONNECTED }

    public interface Listener {
        default void onStatus(Status status) { }
        /** Receives every JSON message, including correlated replies and unsolicited events. */
        default void onMessage(Message message) { }
        /** Transport only: the application must validate transfer grants/order before storing data. */
        default void onChunk(FileChunk chunk) { }
        default void onError(Throwable error) { }
    }

    private static final class Connection {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final Map<String, CompletableFuture<Message>> pending = new HashMap<>();
        Connection(Socket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Executor callbacks;
    private final Object lifecycle = new Object();
    private Connection connection;

    public NetworkClient() { this(DEFAULT_HOST, DEFAULT_PORT); }
    public NetworkClient(String host, int port) { this(host, port, new Listener() { }, Runnable::run); }
    public NetworkClient(String host, int port, Listener listener, Executor callbacks) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A host and port between 1 and 65535 are required.");
        }
        this.host = host;
        this.port = port;
        this.listener = Objects.requireNonNull(listener);
        this.callbacks = Objects.requireNonNull(callbacks);
    }

    public void connect() throws IOException { connect(Duration.ofSeconds(5)); }

    public void connect(Duration timeout) throws IOException {
        long millis = positiveMillis(timeout);
        if (millis > Integer.MAX_VALUE) throw new IllegalArgumentException("Connect timeout is too large.");
        synchronized (lifecycle) {
            if (connection != null) throw new IllegalStateException("Already connected.");
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), (int) millis);
                Connection current = new Connection(socket);
                connection = current;
                dispatch(() -> listener.onStatus(Status.CONNECTED));
                Thread receiver = new Thread(() -> receive(current), "gramalink-client-receiver");
                receiver.setDaemon(true);
                receiver.start();
            } catch (IOException | RuntimeException error) {
                try { socket.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
                dispatch(() -> listener.onError(error));
                throw error;
            }
        }
    }

    public boolean isConnected() {
        synchronized (lifecycle) { return connection != null; }
    }

    public void send(Message message) throws IOException {
        Objects.requireNonNull(message);
        write(current(), output -> JsonUtil.writeMessage(output, message));
    }

    /** Only send bytes after the server has authorized a transfer with FILE_META/READY. */
    public void sendChunk(FileChunk chunk) throws IOException {
        Objects.requireNonNull(chunk);
        write(current(), output -> TransportCodec.writeChunk(output, chunk));
    }

    /** Generates an ID if absent; a server ERROR is a normal reply, not an IO failure. */
    public CompletableFuture<Message> request(Message message, Duration timeout) throws IOException {
        Objects.requireNonNull(message);
        long millis = positiveMillis(timeout);
        String id = message.correlationId() == null ? UUID.randomUUID().toString() : message.correlationId();
        if (id.isBlank()) throw new IllegalArgumentException("Correlation ID must not be blank.");
        Message outbound = new Message(message.type(), id, message.token(), message.payload().deepCopy());
        CompletableFuture<Message> reply = new CompletableFuture<>();
        Connection current;
        synchronized (lifecycle) {
            current = current();
            if (current.pending.putIfAbsent(id, reply) != null) {
                throw new IllegalArgumentException("Correlation ID is already pending.");
            }
        }
        reply.whenComplete((value, error) -> {
            synchronized (lifecycle) { current.pending.remove(id, reply); }
        });
        reply.orTimeout(millis, TimeUnit.MILLISECONDS);
        try { write(current, output -> JsonUtil.writeMessage(output, outbound)); }
        catch (IOException error) { reply.completeExceptionally(error); throw error; }
        return reply;
    }

    private Connection current() throws IOException {
        synchronized (lifecycle) {
            if (connection == null) throw new IOException("Client is disconnected.");
            return connection;
        }
    }

    @FunctionalInterface
    private interface Write { void run(DataOutputStream output) throws IOException; }

    private void write(Connection current, Write operation) throws IOException {
        try {
            // All JSON and binary writes share this exact wrapper and frame lock.
            synchronized (current.output) { operation.run(current.output); }
        } catch (IOException error) {
            finish(current, error);
            throw error;
        }
    }

    private void receive(Connection current) {
        try {
            TransportFrame frame;
            while ((frame = TransportCodec.readFrame(current.input)) != null) {
                synchronized (lifecycle) { if (connection != current) return; }
                if (frame instanceof TransportFrame.JsonFrame json) {
                    Message message = json.message();
                    CompletableFuture<Message> pending;
                    synchronized (lifecycle) { pending = current.pending.remove(message.correlationId()); }
                    if (pending != null) pending.complete(message);
                    dispatch(() -> listener.onMessage(message));
                } else if (frame instanceof FileChunk chunk) {
                    dispatch(() -> listener.onChunk(chunk));
                }
            }
            finish(current, null);
        } catch (IOException error) { finish(current, error); }
    }

    private void finish(Connection current, IOException error) {
        List<CompletableFuture<Message>> pending;
        IOException reason;
        synchronized (lifecycle) {
            if (connection != current) return;
            connection = null;
            try { current.socket.close(); }
            catch (IOException closeError) { if (error == null) error = closeError; else error.addSuppressed(closeError); }
            pending = List.copyOf(current.pending.values());
            current.pending.clear();
            reason = error == null ? new EOFException("Connection closed.") : error;
            if (error != null) {
                IOException reported = error;
                dispatch(() -> listener.onError(reported));
            }
            dispatch(() -> listener.onStatus(Status.DISCONNECTED));
        }
        // Future continuations are application code; never run them under our lifecycle lock.
        pending.forEach(future -> future.completeExceptionally(reason));
    }

    public void disconnect() {
        Connection current;
        synchronized (lifecycle) { current = connection; }
        if (current != null) finish(current, null);
    }
    @Override public void close() { disconnect(); }

    private void dispatch(Runnable callback) {
        // UI/listener failures must not terminate the transport receiver.
        try { callbacks.execute(() -> { try { callback.run(); } catch (RuntimeException ignored) { } }); }
        catch (RuntimeException ignored) { }
    }

    private static long positiveMillis(Duration duration) {
        Objects.requireNonNull(duration);
        long millis = duration.toMillis();
        if (duration.isNegative() || millis < 1) throw new IllegalArgumentException("Timeout must be at least 1 ms.");
        return millis;
    }
}
