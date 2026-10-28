package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A handler that says how far it got should be able to see how far it got.
 *
 * <p>The report has to reach the store while the job is still running, and it has to be
 * cheap enough that a handler can report as often as it likes.</p>
 */
@ExtendWith(MockitoExtension.class)
class JobExecutorProgressTest {

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
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();
    }

    @Test
    void shouldWriteTheFirstProgressReportStraightAway() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        handlerRegistry.register("test", ctx -> {
            ctx.setProgress(25.0, "step one");
            reported.countDown();
            return "done";
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        doAnswer(inv -> null).when(retryService).logSuccess(any(), anyString(), anyLong());

        service.execute(job);
        assertTrue(reported.await(5, TimeUnit.SECONDS));

        // The first report is written without waiting for the throttle window: a job that
        // reports once and finishes should still be visible as having made progress.
        verify(jobRepo).updateProgress("job-1", 25.0, "step one");
    }

    @Test
    void shouldThrottleTheReportsOfAHandlerThatReportsInALoop() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        handlerRegistry.register("test", ctx -> {
            for (int i = 1; i <= 50; i++) {
                ctx.setProgress(i * 2.0, "step " + i);
            }
            done.countDown();
            return "done";
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        doAnswer(inv -> null).when(retryService).logSuccess(any(), anyString(), anyLong());

        service.execute(job);
        assertTrue(done.await(5, TimeUnit.SECONDS));

        // Fifty reports inside the throttle window must not become fifty round trips.
        verify(jobRepo, atLeastOnce()).updateProgress(eq("job-1"), anyDouble(), anyString());
        verify(jobRepo, org.mockito.Mockito.times(1)).updateProgress(eq("job-1"), anyDouble(), anyString());
    }

    @Test
    void shouldEndASuccessfulJobAtOneHundredPercent() throws Exception {
        handlerRegistry.register("test", ctx -> {
            ctx.setProgress(50.0, "halfway");
            return "done";
        });

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        doAnswer(inv -> null).when(retryService).logSuccess(any(), anyString(), anyLong());

        service.execute(job);

        org.mockito.ArgumentCaptor<JobEntity> captor = org.mockito.ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo, org.mockito.Mockito.timeout(5000).atLeastOnce()).saveIfLeaseHeld(captor.capture(), anyString());
        assertEquals(100.0, captor.getValue().getProgress());
    }

    @Test
    void shouldNotWriteProgressForAJobThatReportsNothing() throws Exception {
        handlerRegistry.register("test", ctx -> "done");

        JobEntity job = job();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);
        doAnswer(inv -> null).when(retryService).logSuccess(any(), anyString(), anyLong());

        service.execute(job);

        verify(jobRepo, org.mockito.Mockito.timeout(5000).atLeastOnce()).saveIfLeaseHeld(any(), anyString());
        verify(jobRepo, never()).updateProgress(anyString(), org.mockito.ArgumentMatchers.anyDouble(), anyString());
    }
}
