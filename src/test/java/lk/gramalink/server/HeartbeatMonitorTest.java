package lk.gramalink.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatMonitorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(35);
    private static final Duration INTERVAL = Duration.ofSeconds(5);

    @Test
    void defaultsAllowTenSecondClientHeartbeats() {
        assertEquals(TIMEOUT, HeartbeatMonitor.DEFAULT_TIMEOUT);
        assertEquals(INTERVAL, HeartbeatMonitor.DEFAULT_CHECK_INTERVAL);
    }

    @Test
    void startSchedulesExactlyOneTaskWithConfiguredFixedDelay() {
        var scheduler = new ControlledScheduler();
        try (var monitor = new HeartbeatMonitor(new SessionManager(), TIMEOUT, Duration.ofMillis(250), scheduler)) {
            assertEquals(0, scheduler.scheduleCount);
            monitor.start();
            monitor.start();
            assertEquals(1, scheduler.scheduleCount);
            assertEquals(Duration.ofMillis(250).toNanos(), scheduler.initialDelay);
            assertEquals(Duration.ofMillis(250).toNanos(), scheduler.delay);
            assertEquals(TimeUnit.NANOSECONDS, scheduler.unit);
            assertFalse(monitor.isShutdown());
        }
    }

    @Test
    void scheduledChecksUseControlledTimeWithoutWaitingForTheTimeout() throws IOException {
        var now = new AtomicLong();
        var sessions = new SessionManager(now::get);
        var scheduler = new ControlledScheduler();
        try (SocketPair pair = new SocketPair();
             var monitor = new HeartbeatMonitor(sessions, TIMEOUT, INTERVAL, scheduler)) {
            // Test-only stand-in for successful backend identity verification.
            sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity("verified-user", "CITIZEN"),
                    pair.server);
            monitor.start();

            now.set(Duration.ofSeconds(34).toNanos());
            scheduler.runCheck();
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertFalse(pair.server.isClosed());

            now.set(TIMEOUT.toNanos());
            scheduler.runCheck();
            assertEquals(0, sessions.getAuthenticatedSessionCount());
            assertTrue(pair.server.isClosed());
        }
    }

    @Test
    void aFailedCheckDoesNotStopLaterScheduledChecks() throws IOException {
        var now = new AtomicLong();
        var failNextRead = new AtomicBoolean();
        var sessions = new SessionManager(() -> {
            if (failNextRead.getAndSet(false)) {
                throw new IllegalStateException("Test-only failure; no client data should be logged.");
            }
            return now.get();
        });
        var scheduler = new ControlledScheduler();
        try (SocketPair pair = new SocketPair();
             var monitor = new HeartbeatMonitor(sessions, TIMEOUT, INTERVAL, scheduler)) {
            sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity("verified-user", "CITIZEN"),
                    pair.server);
            monitor.start();
            now.set(TIMEOUT.toNanos());
            failNextRead.set(true);
            assertDoesNotThrow(scheduler::runCheck);
            assertFalse(pair.server.isClosed());

            scheduler.runCheck();
            assertTrue(pair.server.isClosed());
            assertEquals(0, sessions.getAuthenticatedSessionCount());
        }
    }

    @Test
    void closeStopsTheSchedulerAndPreventsFurtherChecks() throws IOException {
        var now = new AtomicLong();
        var sessions = new SessionManager(now::get);
        var scheduler = new ControlledScheduler();
        try (SocketPair pair = new SocketPair()) {
            sessions.registerVerifiedSession(new SessionManager.VerifiedIdentity("verified-user", "CITIZEN"),
                    pair.server);
            var monitor = new HeartbeatMonitor(sessions, TIMEOUT, INTERVAL, scheduler);
            monitor.start();
            monitor.close();
            monitor.close();
            assertTrue(monitor.isShutdown());
            assertEquals(1, scheduler.shutdownCount);
            assertEquals(1, scheduler.awaitCount);
            now.set(TIMEOUT.toNanos());
            scheduler.runCheck();
            assertFalse(pair.server.isClosed());
            assertEquals(1, sessions.getAuthenticatedSessionCount());
            assertThrows(IllegalStateException.class, monitor::start);
        }
    }

    @Test
    void monitorCanBeClosedBeforeItStarts() {
        var scheduler = new ControlledScheduler();
        var monitor = new HeartbeatMonitor(new SessionManager(), TIMEOUT, INTERVAL, scheduler);
        monitor.close();
        assertTrue(monitor.isShutdown());
        assertEquals(0, scheduler.scheduleCount);
        assertThrows(IllegalStateException.class, monitor::start);
    }

    @Test
    void closePreservesInterruption() {
        var scheduler = new ControlledScheduler();
        scheduler.interruptAwait = true;
        var monitor = new HeartbeatMonitor(new SessionManager(), TIMEOUT, INTERVAL, scheduler);
        try {
            monitor.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(monitor.isShutdown());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void networkServerStartsAndStopsItsOwnedHeartbeatMonitor() throws Exception {
        var sessions = new SessionManager();
        var scheduler = new ControlledScheduler();
        var monitor = new HeartbeatMonitor(sessions, TIMEOUT, INTERVAL, scheduler);
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        var failure = new AtomicReference<Throwable>();
        try (var server = new NetworkServer(port, sessions, monitor)) {
            var listener = new Thread(() -> {
                try {
                    server.start();
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            }, "heartbeat-server-lifecycle-test");
            listener.start();
            try {
                assertTrue(scheduler.scheduled.await(5, TimeUnit.SECONDS),
                        "Starting the server must start its heartbeat monitoring task.");
                assertEquals(1, scheduler.scheduleCount);
            } finally {
                server.close();
                listener.join(5000);
            }
            assertFalse(listener.isAlive(), "Closing the server must unblock accept().");
            assertTrue(monitor.isShutdown());
            assertEquals(1, scheduler.shutdownCount);
            assertNull(failure.get(), "Listener shutdown must not fail.");
        }
    }

    @Test
    void invalidConfigurationIsRejectedBeforeCreatingAnOwnedScheduler() {
        var sessions = new SessionManager();
        assertThrows(NullPointerException.class, () -> new HeartbeatMonitor(null, TIMEOUT, INTERVAL));
        assertThrows(NullPointerException.class, () -> new HeartbeatMonitor(sessions, null, INTERVAL));
        assertThrows(NullPointerException.class, () -> new HeartbeatMonitor(sessions, TIMEOUT, null));
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(IllegalArgumentException.class, () -> new HeartbeatMonitor(sessions, invalid, INTERVAL));
            assertThrows(IllegalArgumentException.class, () -> new HeartbeatMonitor(sessions, TIMEOUT, invalid));
        }
    }

    @Test
    void ownedSchedulerShutsDownWithoutEverStartingAThread() {
        var monitor = new HeartbeatMonitor(new SessionManager(), TIMEOUT, INTERVAL);
        assertFalse(monitor.isShutdown());
        monitor.close();
        assertTrue(monitor.isShutdown());
    }

    /** Captures a periodic task so tests can execute it with a controlled session clock. */
    private static final class ControlledScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private Runnable check;
        private int scheduleCount;
        private long initialDelay;
        private long delay;
        private TimeUnit unit;
        private boolean shutdown;
        private int shutdownCount;
        private int awaitCount;
        private boolean interruptAwait;
        private final CountDownLatch scheduled = new CountDownLatch(1);

        private void runCheck() {
            assertNotNull(check, "The monitor must have scheduled a task.");
            check.run();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            check = command;
            scheduleCount++;
            this.initialDelay = initialDelay;
            this.delay = delay;
            this.unit = unit;
            scheduled.countDown();
            return new UnusedScheduledFuture();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            shutdownCount++;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            awaitCount++;
            if (interruptAwait) {
                throw new InterruptedException("Test-only interrupted shutdown.");
            }
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SocketPair implements AutoCloseable {
        private final Socket client;
        private final Socket server;

        private SocketPair() throws IOException {
            try (ServerSocket listener = new ServerSocket(0)) {
                client = new Socket("127.0.0.1", listener.getLocalPort());
                server = listener.accept();
            }
        }

        @Override
        public void close() throws IOException {
            try {
                server.close();
            } finally {
                client.close();
            }
        }
    }
}
