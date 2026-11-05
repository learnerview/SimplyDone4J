package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs retry promotion, lease recovery, and orphan reconciliation on a dedicated pool.
 *
 * <p>The default Spring scheduler is single-threaded. When one maintenance task blocks on
 * Redis, the other two stop running, so a delayed lease reaper or retry promoter can leave
 * jobs stuck long after the failure. A dedicated pool prevents the tasks from serialising
 * behind each other and keeps maintenance independent of the application's scheduler.</p>
 */
public final class WorkerMaintenanceScheduler implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WorkerMaintenanceScheduler.class);

    private final WorkerMaintenanceService maintenance;
    private final long retryIntervalMs;
    private final long leaseIntervalMs;
    private final long orphanIntervalMs;
    private final int poolSize;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public WorkerMaintenanceScheduler(WorkerMaintenanceService maintenance, SimplyDoneProperties props) {
        this.maintenance = maintenance;
        this.retryIntervalMs = Math.max(1L, props.getWorker().getRetryPromoterIntervalMs());
        this.leaseIntervalMs = Math.max(1L, props.getWorker().getLeaseReaperIntervalMs());
        this.orphanIntervalMs = Math.max(1L, props.getWorker().getOrphanReconcileIntervalMs());
        this.poolSize = Math.max(1, props.getWorker().getMaintenancePoolSize());
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger sequence = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable,
                    "simplydone4j-maintenance-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(guarded("retry promoter", maintenance::promoteRetries),
                0L, retryIntervalMs, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(guarded("lease reaper", maintenance::recoverExpiredLeases),
                0L, leaseIntervalMs, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(guarded("orphan reconciler", maintenance::reconcileOrphanedQueuedJobs),
                0L, orphanIntervalMs, TimeUnit.MILLISECONDS);
        log.debug("Started SimplyDone4J maintenance scheduler with {} thread(s)", poolSize);
    }

    public synchronized boolean isRunning() {
        return started.get() && executor != null && !executor.isShutdown();
    }

    public synchronized void close() {
        started.set(false);
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("SimplyDone4J maintenance scheduler did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor = null;
        }
    }

    private Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                log.error("SimplyDone4J {} failed; the next run will retry", name, error);
            }
        };
    }
}
