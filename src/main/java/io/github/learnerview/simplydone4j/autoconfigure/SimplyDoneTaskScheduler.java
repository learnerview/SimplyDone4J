package io.github.learnerview.simplydone4j.autoconfigure;

import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The library's own timer.
 *
 * <p>Spring Boot's {@code TaskSchedulingAutoConfiguration} backs off completely when the
 * application context contains a {@code ScheduledExecutorService} or {@code TaskScheduler}
 * bean, so publishing one of those from a starter silently takes the <em>host
 * application's</em> {@code @Scheduled} methods with it. The library therefore keeps its
 * timer to itself: it owns the executor, exposes it only as {@code ScheduledExecutorService}
 * to its own components, and never publishes a bean of either type.</p>
 *
 * <p>The pool is deliberately larger than one thread. The scheduler poll, the retry
 * promoter, the lease reaper, the queue-depth sampler and the per-job watchdog all run
 * here; a poll that blocks on a slow Redis must not delay a watchdog that is about to
 * record a timeout, nor a lease renewal that keeps a running job from being reclaimed.</p>
 */
public final class SimplyDoneTaskScheduler implements DisposableBean {

    private final ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

    public SimplyDoneTaskScheduler(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /**
     * Creates a scheduler with its own daemon threads.
     *
     * @param threadNamePrefix prefix for the thread names, e.g. {@code sd4j-timer}
     * @param poolSize number of timer threads
     */
    public static SimplyDoneTaskScheduler create(String threadNamePrefix, int poolSize) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(Math.max(1, poolSize), factory);
        return new SimplyDoneTaskScheduler(executor);
    }

    /**
     * The underlying executor, for the components that need a one-shot schedule (the
     * handler watchdog and the lease renewer).
     */
    public ScheduledExecutorService executor() {
        return executor;
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(task, delay, unit);
    }

    /**
     * Schedules a repeating task and remembers it so that {@link #shutdown()} can cancel
     * it. The task must not throw: {@code scheduleWithFixedDelay} silently stops
     * repeating a task that raises.
     */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay, Duration initialDelay) {
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(task,
                initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
        tasks.add(future);
        return future;
    }

    public void shutdown() {
        tasks.forEach(future -> future.cancel(false));
        tasks.clear();
        executor.shutdownNow();
    }

    @Override
    public void destroy() {
        shutdown();
    }
}
