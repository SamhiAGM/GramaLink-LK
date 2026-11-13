package lk.gramalink.server;

import java.io.IOException;
import java.net.BindException;
import java.time.Duration;

/** Command-line entry point for the GramaLink TCP server. */
public final class ServerMain {
    private static final int DEFAULT_PORT = 5000;

    private ServerMain() {
    }

    public static void main(String[] args) {
        final int port;
        try {
            port = parsePort(args);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid port: " + exception.getMessage());
            System.err.println("Usage: java -cp target/classes lk.gramalink.server.ServerMain [port]");
            System.exit(1);
            return;
        }

        final Duration heartbeatTimeout;
        final Duration heartbeatCheckInterval;
        try {
            heartbeatTimeout = secondsProperty("gramalink.heartbeat.timeoutSeconds", HeartbeatMonitor.DEFAULT_TIMEOUT);
            heartbeatCheckInterval = secondsProperty("gramalink.heartbeat.checkIntervalSeconds",
                    HeartbeatMonitor.DEFAULT_CHECK_INTERVAL);
        } catch (IllegalArgumentException exception) {
            System.err.println("Invalid heartbeat configuration: " + exception.getMessage());
            System.exit(1);
            return;
        }

        final NetworkServer server;
        try {
            var sessions = new SessionManager();
            var database = lk.gramalink.server.repository.Database.fromEnvironment();
            database.transaction(c -> { lk.gramalink.server.repository.Sql.rows(c, "SELECT id FROM users LIMIT 1"); return null; });
            var files = new lk.gramalink.server.service.FileTransferService(database, sessions,
                    java.nio.file.Path.of(System.getenv().getOrDefault("GRAMALINK_STORAGE", "storage")),
                    Long.parseLong(System.getenv().getOrDefault("GRAMALINK_MAX_FILE_BYTES", "10485760")),
                    java.util.Set.of(System.getenv().getOrDefault("GRAMALINK_ALLOWED_MEDIA_TYPES", "application/pdf").split(",")));
            server = new NetworkServer(port, sessions, heartbeatTimeout, heartbeatCheckInterval, files,
                    new lk.gramalink.server.backend.BackendDispatcher(database, sessions, files));
        } catch (BindException exception) {
            System.err.println("Unable to start GramaLink Server on port " + port
                    + ": port is already in use or unavailable. " + exception.getMessage());
            System.exit(1);
            return;
        } catch (IOException | RuntimeException exception) {
            System.err.println("Unable to start GramaLink Server on port " + port
                    + ": backend configuration, database, or storage is unavailable.");
            System.exit(1);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException exception) {
                System.err.println("Unable to close server socket: " + exception.getMessage());
            }
        }, "gramalink-shutdown"));

        try (server) {
            server.start();
        } catch (IOException exception) {
            System.err.println("GramaLink Server listener failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Duration secondsProperty(String name, Duration defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0) {
                throw new IllegalArgumentException();
            }
            Duration duration = Duration.ofSeconds(seconds);
            duration.toNanos();
            return duration;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IllegalArgumentException(name + " must be positive whole seconds within the supported range.");
        }
    }

    private static int parsePort(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected at most one port argument.");
        }
        if (args.length == 0) {
            return DEFAULT_PORT;
        }

        final int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Use a whole number between 1 and 65535.");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        return port;
    }
}
