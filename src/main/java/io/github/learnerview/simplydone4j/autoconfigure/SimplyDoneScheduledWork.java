package io.github.learnerview.simplydone4j.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * The library's periodic work, scheduled on the library's own timer.
 *
 * <p>Each task is wrapped so that it cannot kill its own schedule: a
 * {@code scheduleWithFixedDelay} task that raises is silently never repeated, and a dead
 * poller, retry promoter or lease reaper is enough to stall the pipeline. The failure is
 * logged instead of propagated, and the next run happens as scheduled.</p>
 */
public final class SimplyDoneScheduledWork implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SimplyDoneScheduledWork.class);

    private final SimplyDoneTaskScheduler scheduler;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    public SimplyDoneScheduledWork(SimplyDoneTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Schedules a repeating task.
     *
     * @param name          identifies the task in the logs
     * @param task          the work; exceptions are caught and logged
     * @param delay         the delay between the end of one run and the start of the next
     * @param initialDelay  how long to wait before the first run
     */
    public void schedule(String name, Runnable task, Duration delay, Duration initialDelay) {
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> runSafely(name, task), delay, initialDelay);
        futures.add(future);
        log.debug("Scheduled {} every {} (first run in {})", name, delay, initialDelay);
    }

    private void runSafely(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("Scheduled task {} failed", name, e);
        }
    }

    /**
     * Cancels every scheduled task. The timer itself is shut down by its own bean.
     */
    public void cancel() {
        futures.forEach(future -> future.cancel(false));
        futures.clear();
    }

    @Override
    public void destroy() {
        cancel();
    }
}
