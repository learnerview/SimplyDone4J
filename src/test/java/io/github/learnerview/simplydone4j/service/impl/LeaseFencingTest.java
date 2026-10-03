package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WebhookService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The fencing guarantee: a worker whose lease has already been reaped and handed to
 * somebody else must never be able to record an outcome for the job.
 *
 * <p>Every terminal write goes through {@code saveIfLeaseHeld}, which compares the token
 * and performs the write under a single optimistic transaction. These tests pin the two
 * properties that makes meaningful: the token compared is the one the worker was issued
 * (not whatever it happens to read back), and a rejected write leaves no observable trace.
 */
@ExtendWith(MockitoExtension.class)
class LeaseFencingTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;
    @Mock WebhookService webhookService;

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
        executor.setThreadNamePrefix("fencing-test-");
        executor.initialize();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        service = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher,
                webhookService, executor, timeoutScheduler, 30);
    }

    @AfterEach
    void tearDown() {
        timeoutScheduler.shutdownNow();
    }

    @Test
    void shouldRejectSuccessWhenLeaseTokenIsClearedByReaper() throws Exception {
        CountDownLatch fencedWriteAttempted = new CountDownLatch(1);
        handlerRegistry.register("slow-job", ctx -> null);

        JobEntity originalJob = runningJob("job-with-expired-lease", "slow-job");

        // The reaper already cleared the lease and moved the job on, so the store will
        // reject any write that does not present the original token.
        when(jobRepo.findById("job-with-expired-lease"))
                .thenReturn(Optional.of(reapedJob("job-with-expired-lease", JobStatus.RETRY_SCHEDULED)));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenAnswer(invocation -> {
            fencedWriteAttempted.countDown();
            return false;
        });

        service.execute(originalJob);

        assert fencedWriteAttempted.await(10, TimeUnit.SECONDS) : "Fenced write was never attempted";

        verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
        verify(webhookService, never()).fireCallback(any(), anyString(), any());
    }

    @Test
    void shouldCompareTheIssuedLeaseTokenNotTheRereadOne() throws Exception {
        CountDownLatch fencedWriteAttempted = new CountDownLatch(1);
        handlerRegistry.register("slow-job", ctx -> null);

        JobEntity originalJob = runningJob("job-token-check", "slow-job");

        when(jobRepo.findById("job-token-check"))
                .thenReturn(Optional.of(runningJob("job-token-check", "slow-job")));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenAnswer(invocation -> {
            fencedWriteAttempted.countDown();
            return true;
        });

        service.execute(originalJob);

        assert fencedWriteAttempted.await(10, TimeUnit.SECONDS) : "Fenced write was never attempted";

        // If the worker compared the token it read back rather than the one it was issued,
        // a stolen lease would go undetected: both sides would show the reaper's value.
        verify(jobRepo).saveIfLeaseHeld(any(), eq("issued-lease-token"));
    }

    @Test
    void shouldRejectFailureWhenLeaseTokenIsClearedByReaper() throws Exception {
        CountDownLatch failureAttempted = new CountDownLatch(1);
        handlerRegistry.register("failing-job", ctx -> {
            throw new RuntimeException("handler failed");
        });

        JobEntity originalJob = runningJob("job-with-expired-lease-fail", "failing-job");

        when(jobRepo.findById("job-with-expired-lease-fail"))
                .thenReturn(Optional.of(reapedJob("job-with-expired-lease-fail", JobStatus.DLQ)));

        when(retryService.handleFailureIfLeaseHeld(any(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    failureAttempted.countDown();
                    return null;
                });

        service.execute(originalJob);

        assert failureAttempted.await(10, TimeUnit.SECONDS) : "Fenced failure was never attempted";

        // A null result means the lease was lost and the write discarded, so no callback
        // may be fired for an outcome the store never accepted.
        verify(retryService).handleFailureIfLeaseHeld(any(), eq("issued-lease-token"), anyString(), anyLong());
        verify(webhookService, never()).fireCallback(any(), anyString(), any());
        verify(eventPublisher, never()).publish(eq(JobEvent.JOB_COMPLETED), any());
    }

    private JobEntity runningJob(String id, String jobType) {
        return JobEntity.builder()
                .id(id)
                .jobType(jobType)
                .producer("worker-a")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken("issued-lease-token")
                .leaseOwner("worker-a")
                .attemptCount(0)
                .maxAttempts(3)
                .build();
    }

    private JobEntity reapedJob(String id, JobStatus statusAfterReap) {
        return JobEntity.builder()
                .id(id)
                .jobType("slow-job")
                .producer("worker-a")
                .status(statusAfterReap)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken(null)
                .leaseOwner(null)
                .attemptCount(1)
                .maxAttempts(3)
                .build();
    }
}
