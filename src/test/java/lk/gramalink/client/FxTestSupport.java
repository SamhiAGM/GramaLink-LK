package lk.gramalink.client;

import javafx.application.Platform;
import java.util.concurrent.*;

/** One FX toolkit per Surefire JVM, shared across Citizen and GN tests. */
final class FxTestSupport {
    private static boolean started;
    private FxTestSupport() { }
    static synchronized void start() throws Exception {
        if (started) return;
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.complete(null); });
        ready.get(10, TimeUnit.SECONDS);
        started = true;
        // Surefire exits the fork after all suites; do not stop FX between dashboard classes.
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(5, TimeUnit.SECONDS);
    }
}
