package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One scheduler thread per application instance.
 *
 * <p>The loop is event-driven but never purely event-driven: submissions, retry
 * promotions and recovered leases signal it, while an idle timeout guarantees that a
 * lost signal cannot leave a queue unserved. When work was claimed it wakes again
 * quickly, which keeps batched claims flowing while the executor still has capacity.</p>
 */
public final class SchedulerLoop implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final SchedulerService scheduler;
    private final JobSignal signal;
    private final long idleDelayMs;
    private final long busyDelayMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public SchedulerLoop(SchedulerService scheduler, JobSignal signal, SimplyDoneProperties config) {
        this.scheduler = scheduler;
        this.signal = signal;
        this.idleDelayMs = Math.max(1, config.getScheduler().getPollingIntervalMs());
        this.busyDelayMs = Math.max(1, Math.min(idleDelayMs,
                config.getScheduler().getBusyPollingIntervalMs()));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        thread = Thread.ofPlatform()
                .daemon(true)
                .name("simplydone4j-scheduler")
                .start(this::run);
    }

    public synchronized boolean isRunning() {
        return running.get() && thread != null && thread.isAlive();
    }

    public synchronized void stop() {
        running.set(false);
        Thread current = thread;
        if (current == null) return;
        current.interrupt();
        try {
            current.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (current.isAlive()) {
            log.warn("Scheduler thread did not stop within 5 seconds; refusing to start another");
            return;
        }
        thread = null;
    }

    private void run() {
        try {
            while (running.get()) {
                int claimed = 0;
                try {
                    claimed = scheduler.pollOnce();
                } catch (Exception e) {
                    log.error("Scheduler poll failed", e);
                }

                long delay = claimed > 0 ? busyDelayMs : idleDelayMs;
                try {
                    signal.await(delay);
                } catch (InterruptedException e) {
                    if (!running.get()) return;
                }
            }
        } finally {
            running.set(false);
        }
    }
}
