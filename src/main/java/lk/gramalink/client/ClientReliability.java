package lk.gramalink.client;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lk.gramalink.shared.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Transport recovery only. Never stores credentials, replays LOGIN/actions, or grants authentication. */
public final class ClientReliability implements AutoCloseable {
    public enum State { CONNECTING, ONLINE, RECONNECTING, OFFLINE, AUTHENTICATION_REQUIRED }
    public record Policy(Duration connectTimeout, Duration heartbeatInterval, Duration heartbeatReplyTimeout,
                         Duration serverHeartbeatTimeout, Duration initialBackoff, Duration maximumBackoff, int maximumAttempts) {
        public Policy {
            for (Duration value : new Duration[]{connectTimeout, heartbeatInterval, heartbeatReplyTimeout, serverHeartbeatTimeout, initialBackoff, maximumBackoff})
                if (value == null || value.toMillis() < 1) throw new IllegalArgumentException("Reliability durations must be positive milliseconds.");
            if (connectTimeout.toMillis() > Integer.MAX_VALUE || heartbeatInterval.plus(heartbeatReplyTimeout).compareTo(serverHeartbeatTimeout) >= 0
                    || initialBackoff.compareTo(maximumBackoff) > 0 || maximumAttempts < 1)
                throw new IllegalArgumentException("Invalid heartbeat/backoff bounds.");
        }
        public static Policy defaults() {
            // Matches Samhi's default 35-second authenticated heartbeat expiry.
            return new Policy(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(35),
                    Duration.ofMillis(500), Duration.ofSeconds(8), 5);
        }
        public Duration backoff(int attempt) {
            if (attempt < 1) throw new IllegalArgumentException("Attempt numbers start at one.");
            long millis = initialBackoff.toMillis(), maximum = maximumBackoff.toMillis();
            for (int i = 1; i < attempt && millis < maximum; i++) millis = millis > maximum / 2 ? maximum : millis * 2;
            return Duration.ofMillis(Math.min(millis, maximum));
        }
    }
    private final NetworkClient client;
    private final Policy policy;
    private final Consumer<State> listener;
    private final Runnable revoke;
    private final Object lock = new Object();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "gramalink-reliability-clock"));
    private final ExecutorService io = Executors.newSingleThreadExecutor(task -> daemon(task, "gramalink-recovery-io"));
    private ScheduledFuture<?> heartbeat, deadline, retry;
    private long generation;
    private int attempts;
    private boolean connected, authenticated, closed, retryQueued, heartbeatPending;
    private volatile State state = State.CONNECTING;

    ClientReliability(NetworkClient client, Policy policy, Consumer<State> listener, Runnable revoke) {
        this.client = Objects.requireNonNull(client); this.policy = Objects.requireNonNull(policy);
        this.listener = Objects.requireNonNull(listener); this.revoke = Objects.requireNonNull(revoke);
    }
    public State state() { return state; }
    public long generation() { synchronized (lock) { return generation; } }
    void connected() {
        synchronized (lock) {
            if (closed) return;
            generation++; connected = true; authenticated = false; retryQueued = false; heartbeatPending = false;
            cancel(heartbeat); cancel(deadline); cancel(retry); state = State.AUTHENTICATION_REQUIRED;
        }
        announce(State.AUTHENTICATION_REQUIRED);
    }
    /** Only LoginService calls this after exactly one role adapter validates the matched server reply. */
    void authenticated() {
        synchronized (lock) {
            if (closed || !connected || authenticated) return;
            authenticated = true; attempts = 0; state = State.ONLINE;
            long epoch = generation;
            heartbeat = timer.schedule(() -> heartbeat(epoch), policy.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
        announce(State.ONLINE);
    }
    void transportDisconnected() {
        synchronized (lock) {
            if (closed || retryQueued) return;
            generation++; connected = false; authenticated = false; heartbeatPending = false;
            cancel(heartbeat); cancel(deadline);
        }
        revoke.run(); scheduleRetry();
    }
    void connectionFailed() { transportDisconnected(); }
    private void scheduleRetry() {
        State announced;
        synchronized (lock) {
            if (closed || connected || retryQueued) return;
            if (attempts >= policy.maximumAttempts()) { state = State.OFFLINE; announced = State.OFFLINE; }
            else {
                attempts++; retryQueued = true; state = State.RECONNECTING; announced = State.RECONNECTING;
                long epoch = generation;
                retry = timer.schedule(() -> reconnect(epoch), policy.backoff(attempts).toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        announce(announced);
    }
    private void reconnect(long epoch) {
        synchronized (lock) { if (closed || connected || generation != epoch) return; }
        try {
            io.execute(() -> {
                synchronized (lock) { if (closed || connected || generation != epoch) return; }
                try {
                    client.connect(policy.connectTimeout());
                    boolean stopped;
                    synchronized (lock) { stopped = closed; }
                    if (stopped) client.disconnectForRecovery();
                    // NetworkClient's synchronous status callback calls connected().
                } catch (Exception error) {
                    synchronized (lock) { if (closed || generation != epoch) return; retryQueued = false; }
                    scheduleRetry();
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }
    private void heartbeat(long epoch) {
        synchronized (lock) {
            if (closed || !connected || !authenticated || generation != epoch || heartbeatPending) return;
            heartbeatPending = true;
            // Starts before a potentially blocked write, so the clock can close the socket independently.
            deadline = timer.schedule(() -> heartbeatFailed(epoch), policy.heartbeatReplyTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            io.execute(() -> {
                synchronized (lock) { if (closed || !authenticated || generation != epoch) return; }
                try {
                    client.request(new Message(MessageType.HEARTBEAT, JsonNodeFactory.instance.objectNode()), policy.heartbeatReplyTimeout())
                            .whenComplete((reply, error) -> {
                                if (error != null || reply.type() != MessageType.ACK || reply.token() != null
                                        || !reply.payload().path("acknowledgedType").asText().equals("HEARTBEAT")) {
                                    heartbeatFailed(epoch); return;
                                }
                                synchronized (lock) {
                                    if (closed || !authenticated || generation != epoch) return;
                                    cancel(deadline); heartbeatPending = false;
                                    heartbeat = timer.schedule(() -> heartbeat(epoch), policy.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
                                }
                            });
                } catch (Exception error) { heartbeatFailed(epoch); }
            });
        } catch (RejectedExecutionException ignored) { }
    }
    private void heartbeatFailed(long epoch) {
        synchronized (lock) {
            if (closed || !connected || !authenticated || generation != epoch) return;
            authenticated = false;
        }
        // Revoke before closing. Never call network APIs while holding the reliability lock.
        revoke.run(); client.disconnectForRecovery();
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; generation++; authenticated = false; connected = false; state = State.OFFLINE;
            cancel(heartbeat); cancel(deadline); cancel(retry);
        }
        timer.shutdownNow(); io.shutdownNow(); announce(State.OFFLINE);
    }
    private void announce(State value) { if (state == value) listener.accept(value); }
    private static void cancel(Future<?> future) { if (future != null) future.cancel(false); }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
}
