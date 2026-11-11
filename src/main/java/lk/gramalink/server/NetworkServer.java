package lk.gramalink.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Accepts connections and delegates blocking reads to a fixed worker pool. */
public final class NetworkServer implements Closeable {
    private final ServerSocket serverSocket;
    private final SessionManager sessionManager;
    private final HeartbeatMonitor heartbeatMonitor;
    private final FileTransferService fileTransferService;
    private final ExecutorService workers = Executors.newFixedThreadPool(32);
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;

    public NetworkServer(int port) throws IOException {
        this(port, new SessionManager());
    }

    /** Allows the future backend integration to share the server's session registry. */
    public NetworkServer(int port, SessionManager sessionManager) throws IOException {
        this(port, sessionManager, HeartbeatMonitor.DEFAULT_TIMEOUT, HeartbeatMonitor.DEFAULT_CHECK_INTERVAL);
    }

    public NetworkServer(int port, SessionManager sessionManager, Duration heartbeatTimeout,
                         Duration heartbeatCheckInterval) throws IOException {
        this(port, sessionManager, heartbeatTimeout, heartbeatCheckInterval, FileTransferService.unavailable());
    }

    /** Backend implementations must authorize every transfer; the default service rejects all requests. */
    public NetworkServer(int port, SessionManager sessionManager, Duration heartbeatTimeout,
                         Duration heartbeatCheckInterval, FileTransferService fileTransferService) throws IOException {
        this(port, sessionManager, new HeartbeatMonitor(sessionManager, heartbeatTimeout, heartbeatCheckInterval),
                fileTransferService);
    }

    NetworkServer(int port, SessionManager sessionManager, HeartbeatMonitor heartbeatMonitor) throws IOException {
        this(port, sessionManager, heartbeatMonitor, FileTransferService.unavailable());
    }

    private NetworkServer(int port, SessionManager sessionManager, HeartbeatMonitor heartbeatMonitor,
                          FileTransferService fileTransferService) throws IOException {
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        this.heartbeatMonitor = Objects.requireNonNull(heartbeatMonitor, "Heartbeat monitor is required.");
        try {
            this.fileTransferService = Objects.requireNonNull(fileTransferService, "File service is required.");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535.");
            }
            serverSocket = new ServerSocket(port);
        } catch (IOException | RuntimeException exception) {
            heartbeatMonitor.close();
            throw exception;
        }
    }

    /** Test fixture: ownership of an already-bound loopback listener transfers to this server. */
    NetworkServer(ServerSocket listener, SessionManager sessionManager, HeartbeatMonitor heartbeatMonitor) {
        this.serverSocket = Objects.requireNonNull(listener, "Listener is required.");
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        this.heartbeatMonitor = Objects.requireNonNull(heartbeatMonitor, "Heartbeat monitor is required.");
        this.fileTransferService = FileTransferService.unavailable();
    }

    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (stopping) {
                return;
            }
            heartbeatMonitor.start();
            System.out.println("GramaLink Server started on port " + serverSocket.getLocalPort());
        }

        while (!stopping) {
            try {
                Socket client = serverSocket.accept();
                synchronized (lifecycleLock) {
                    if (stopping) {
                        closeClient(client);
                        return;
                    }
                    connections.add(client);
                    try {
                        workers.execute(new ClientHandler(client, sessionManager, fileTransferService,
                                () -> connections.remove(client)));
                    } catch (RejectedExecutionException exception) {
                        connections.remove(client);
                        closeClient(client);
                        if (!stopping) {
                            System.err.println("Unable to assign client to a worker: " + exception.getMessage());
                        }
                    }
                }
            } catch (IOException exception) {
                if (stopping) {
                    // Closing the listener during shutdown unblocks accept().
                    return;
                }
                System.err.println("Unable to accept client connection: " + exception.getMessage());
            }
        }
    }

    @Override
    public void close() throws IOException {
        Socket[] clients;
        IOException closeFailure = null;
        synchronized (lifecycleLock) {
            if (stopping) {
                return;
            }
            stopping = true;
            try {
                serverSocket.close();
            } catch (IOException exception) {
                closeFailure = exception;
            }
            workers.shutdown();
            clients = connections.toArray(Socket[]::new);
        }
        heartbeatMonitor.close();
        // Includes queued connections, so no socket survives pool shutdown.
        for (Socket client : clients) {
            closeClient(client);
        }
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException exception) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            connections.clear();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void closeClient(Socket client) {
        try {
            client.close();
        } catch (IOException exception) {
            System.err.println("Unable to close client socket: " + exception.getMessage());
        } finally {
            sessionManager.removeSession(client);
        }
    }
}
