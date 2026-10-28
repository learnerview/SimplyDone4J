package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cancellation that only flips a status is not a cancellation.
 *
 * <p>The job has to stop, the record has to say it stopped, and the record has to be
 * written by the worker that owned the job -- the same fencing rule as every other
 * terminal write.</p>
 */
@ExtendWith(MockitoExtension.class)
class JobExecutorCancellationTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;

    HandlerRegistry handlerRegistry = new HandlerRegistry();
    JobExecutorServiceImpl service;
    ThreadPoolTaskExecutor executor;
    ScheduledExecutorService timeoutScheduler;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-worker-");
        executor.initialize();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        service = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher,
                executor, io.github.learnerview.simplydone4j.service.JobExecutorOptions.builder()
                        .timer(timeoutScheduler)
                        .defaultTimeoutSeconds(30)
                        .build());
    }

    @AfterEach
    void tearDown() {
        timeoutScheduler.shutdownNow();
        executor.shutdown();
    }

    private JobEntity job() {
        return JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();
    }

    @Test
    void shouldStopARunningJobWhenItIsCancelled() throws Exception {
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        CountDownLatch observed = new CountDownLatch(1);
        handlerRegistry.register("test", ctx -> {
            handlerRan.set(true);
            // The handler has to ask; the engine cannot interrupt it.
            long deadline = System.currentTimeMillis() + 10_000;
            while (!ctx.isCancellationRequested() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            observed.countDown();
            return "stopped";
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        // The flag is not set when the handler starts, and is set by the time the
        // watcher polls.
        when(jobRepo.isCancellationRequested("job-1")).thenReturn(false, true);

        service.execute(job);

        assertTrue(observed.await(15, TimeUnit.SECONDS), "the handler must observe the cancellation");

        ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo, timeout(10_000).atLeastOnce()).saveIfLeaseHeld(captor.capture(), anyString());
        assertEquals(JobStatus.CANCELLED, captor.getValue().getStatus(),
                "a cancelled job must not be reported as a success");
        verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
        verify(eventPublisher, timeout(10_000).atLeastOnce()).publish(eq(JobEvent.JOB_CANCELLED), any());
    }

    @Test
    void shouldNotRunAJobThatWasCancelledBeforeItStarted() throws Exception {
        AtomicBoolean handlerRan = new AtomicBoolean(false);
        handlerRegistry.register("test", ctx -> {
            handlerRan.set(true);
            return "done";
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        when(jobRepo.isCancellationRequested("job-1")).thenReturn(true);

        service.execute(job);

        ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo, timeout(10_000).atLeastOnce()).saveIfLeaseHeld(captor.capture(), anyString());
        assertEquals(JobStatus.CANCELLED, captor.getValue().getStatus());
        Thread.sleep(200);
        assertTrue(!handlerRan.get(), "a job cancelled before it started must not run at all");
    }

    @Test
    void shouldTellTheHandlerThatCancellationWasRequested() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        handlerRegistry.register("test", (JobContext ctx) -> {
            observed.countDown();
            return null;
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        when(jobRepo.isCancellationRequested("job-1")).thenReturn(false);

        service.execute(job);

        assertTrue(observed.await(5, TimeUnit.SECONDS));
        verify(jobRepo, timeout(10_000).atLeastOnce()).isCancellationRequested("job-1");
    }
}
