package lk.gramalink.server;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One periodic timeout checker for all authenticated connections in a server. */
public final class HeartbeatMonitor implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(35);
    public static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(5);

    private final SessionManager sessionManager;
    private final Duration timeout;
    private final long checkIntervalNanos;
    private final ScheduledExecutorService scheduler;
    private boolean started;
    private volatile boolean closed;

    public HeartbeatMonitor(SessionManager sessionManager, Duration timeout, Duration checkInterval) {
        this(sessionManager, timeout, checkInterval, newScheduler(sessionManager, timeout, checkInterval));
    }

    HeartbeatMonitor(SessionManager sessionManager, Duration timeout, Duration checkInterval,
                     ScheduledExecutorService scheduler) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "Session manager is required.");
        positiveNanos(timeout, "Heartbeat timeout");
        this.timeout = timeout;
        this.checkIntervalNanos = positiveNanos(checkInterval, "Heartbeat check interval");
        this.scheduler = Objects.requireNonNull(scheduler, "Heartbeat scheduler is required.");
    }

    /** Starts exactly one periodic task; the scheduler thread is created on first start. */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Heartbeat monitor is closed.");
        }
        if (started) {
            return;
        }
        scheduler.scheduleWithFixedDelay(this::checkTimeouts, checkIntervalNanos, checkIntervalNanos,
                TimeUnit.NANOSECONDS);
        started = true;
    }

    public boolean isShutdown() {
        return scheduler.isShutdown();
    }

    private void checkTimeouts() {
        if (closed) {
            return;
        }
        try {
            sessionManager.expireSessions(timeout);
        } catch (RuntimeException exception) {
            // Do not log exception messages that could contain application credentials.
            // Contain a failed check so the scheduler can run subsequent checks.
            System.err.println("Heartbeat monitor check failed (" + exception.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            scheduler.shutdownNow();
        }
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static ScheduledExecutorService newScheduler(SessionManager manager, Duration timeout,
                                                         Duration checkInterval) {
        // Validate before creating the owned executor, including duration overflow.
        Objects.requireNonNull(manager, "Session manager is required.");
        positiveNanos(timeout, "Heartbeat timeout");
        positiveNanos(checkInterval, "Heartbeat check interval");
        return Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "gramalink-heartbeat-monitor"));
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " is required.");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large.", exception);
        }
    }
}
