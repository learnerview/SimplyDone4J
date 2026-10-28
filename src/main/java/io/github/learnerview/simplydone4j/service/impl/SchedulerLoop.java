package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;

import java.time.Duration;

/**
 * The scheduler's loop: poll, then wait for something to happen.
 *
 * <p>Two things wake the loop. A signal says that work became runnable -- a submission, a
 * promoted retry, a requeued dead letter -- and the loop looks immediately instead of
 * waiting out the rest of the poll interval. The timeout is the safety net: work also
 * becomes runnable because time passed (a delayed job reached its due date, a lease
 * expired), and nothing announces that, so the loop has to look again on its own.</p>
 *
 * <p>The interval between looks is not fixed. While the queues hold work the loop comes
 * back quickly, because a worker that finishes a job should get the next one at once.
 * When the queues are empty it settles down to the idle interval, so an idle application
 * costs one cheap poll per second rather than one per job.</p>
 */
public final class SchedulerLoop implements ApplicationListener<JobEventPublisher.JobPublishedEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);

    private final SchedulerService scheduler;
    private final JobSignal signal;
    private volatile boolean running;

    public SchedulerLoop(SchedulerService scheduler, JobSignal signal) {
        this.scheduler = scheduler;
        this.signal = signal;
    }

    /**
     * Runs the loop on the calling thread until it is stopped or interrupted.
     */
    public void run() {
        running = true;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                scheduler.poll();
                signal.await(scheduler.nextPollDelay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Scheduler loop iteration failed", e);
                if (!sleep(Duration.ofSeconds(1))) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Starts the loop on its own daemon thread.
     *
     * <p>The thread is the loop's own so that a slow poll cannot delay a watchdog or a
     * lease renewal, and so that stopping the loop never has to wait for the poll
     * interval to elapse.</p>
     */
    public void start() {
        Thread thread = new Thread(this::run, "sd4j-scheduler");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Stops the loop and wakes it so that it notices immediately.
     */
    public void stop() {
        running = false;
        signal.signal();
    }

    /**
     * Wakes the loop when a job becomes runnable.
     */
    @Override
    public void onApplicationEvent(JobEventPublisher.JobPublishedEvent event) {
        if (event.event() == JobEvent.JOB_CREATED || event.event() == JobEvent.JOB_QUEUED) {
            signal.signal();
        }
    }

    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
