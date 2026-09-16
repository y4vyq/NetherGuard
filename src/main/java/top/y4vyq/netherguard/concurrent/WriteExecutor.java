package top.y4vyq.netherguard.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import top.y4vyq.netherguard.NetherGuardPlugin;

/**
 * 单线程写入队列。所有 INSERT / UPDATE / DELETE 串行执行，避免 SQLite 锁冲突。
 */
public final class WriteExecutor implements AutoCloseable {

    private final ExecutorService executor;

    public WriteExecutor(NetherGuardPlugin plugin) {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "NetherGuard-Writer");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    plugin.getLogger().log(Level.SEVERE, "Uncaught exception in writer thread", ex));
            return t;
        });
    }

    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    public Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}