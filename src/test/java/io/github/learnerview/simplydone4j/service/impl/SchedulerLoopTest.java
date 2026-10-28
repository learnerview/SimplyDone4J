package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerLoopTest {

    private final JobSignal signal = new DefaultJobSignal();
    private final AtomicInteger polls = new AtomicInteger();

    private SchedulerService scheduler(Duration delay) {
        return new SchedulerService() {
            @Override
            public void poll() {
                polls.incrementAndGet();
            }

            @Override
            public Duration nextPollDelay() {
                return delay;
            }
        };
    }

    @Test
    void pollsAndThenWaits() throws Exception {
        SchedulerLoop loop = new SchedulerLoop(scheduler(Duration.ofMillis(20)), signal);

        loop.start();
        Thread.sleep(200);
        loop.stop();

        assertThat(polls.get()).isGreaterThan(1);
    }

    /**
     * The point of the signal: a job that was just submitted is picked up at once, not
     * after the rest of the poll interval.
     */
    @Test
    void pollsImmediatelyWhenWorkBecomesRunnable() throws Exception {
        Duration idle = Duration.ofMinutes(5);
        SchedulerLoop loop = new SchedulerLoop(scheduler(idle), signal);

        loop.start();
        Thread.sleep(100);
        int beforeSignal = polls.get();

        signal.signal();

        long deadline = System.currentTimeMillis() + 5000;
        while (polls.get() <= beforeSignal && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(polls.get())
                .as("the signal must wake the loop instead of waiting out the idle interval")
                .isGreaterThan(beforeSignal);
        loop.stop();
    }

    /**
     * The wake-up arrives as an event, because that is how the rest of the library
     * announces what happened to a job.
     */
    @Test
    void wakesOnAJobEventThatMakesWorkRunnable() throws Exception {
        SchedulerLoop loop = new SchedulerLoop(scheduler(Duration.ofMinutes(5)), signal);

        loop.start();
        Thread.sleep(100);
        int before = polls.get();

        loop.onApplicationEvent(event(JobEvent.JOB_CREATED));

        long deadline = System.currentTimeMillis() + 5000;
        while (polls.get() <= before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(polls.get()).isGreaterThan(before);
        loop.stop();
    }

    /**
     * Not every event means there is work to do. A job that started, completed or failed
     * must not make the scheduler look again.
     */
    @Test
    void ignoresEventsThatDoNotMakeWorkRunnable() throws Exception {
        SchedulerLoop loop = new SchedulerLoop(scheduler(Duration.ofMinutes(5)), signal);

        loop.start();
        Thread.sleep(100);
        int before = polls.get();

        loop.onApplicationEvent(event(JobEvent.JOB_STARTED));
        loop.onApplicationEvent(event(JobEvent.JOB_COMPLETED));
        loop.onApplicationEvent(event(JobEvent.JOB_FAILED));

        Thread.sleep(200);

        assertThat(polls.get()).isEqualTo(before);
        loop.stop();
    }

    @Test
    void stopsWhenAsked() throws Exception {
        CountDownLatch polled = new CountDownLatch(1);
        SchedulerService counting = new SchedulerService() {
            @Override
            public void poll() {
                polled.countDown();
            }
        };
        SchedulerLoop loop = new SchedulerLoop(counting, signal);

        loop.start();
        assertThat(polled.await(5, TimeUnit.SECONDS)).isTrue();

        loop.stop();
        Thread.sleep(100);

        int afterStop = polls.get();
        Thread.sleep(200);
        assertThat(polls.get()).isEqualTo(afterStop);
    }

    private JobEventPublisher.JobPublishedEvent event(JobEvent type) {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .nextRunAt(Instant.now())
                .build();
        return new JobEventPublisher.JobPublishedEvent(this, type, JobEventData.from(job));
    }
}
