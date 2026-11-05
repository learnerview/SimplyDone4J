package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.RetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class RetryServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock JobExecutionLogRepository logRepo;
    @Mock JobEventPublisher eventPublisher;
    @Mock RetryPolicy retryPolicy;

    SimplyDoneProperties props = new SimplyDoneProperties();
    RetryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RetryServiceImpl(jobRepo, logRepo, props, eventPublisher, retryPolicy);
    }

    @Test
    void shouldScheduleRetryWhenAttemptsRemain() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        service.handleFailure(job, "Test error", 100L);

        assertEquals(JobStatus.RETRY_SCHEDULED, job.getStatus());
        assertEquals(1, job.getAttemptCount());
        assertNotNull(job.getNextRunAt());
        verify(logRepo).save(any(JobExecutionLog.class));
        verify(jobRepo).save(job);
    }

    @Test
    void shouldMoveToDlqWhenAttemptsExhausted() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(3)
                .maxAttempts(3)
                .build();

        service.handleFailure(job, "Final error", 100L);

        assertEquals(JobStatus.DLQ, job.getStatus());
        assertTrue(job.getResult().contains("Max retries exceeded"));
        assertNotNull(job.getCompletedAt());
        verify(logRepo).save(any(JobExecutionLog.class));
        verify(jobRepo).save(job);
    }

    @Test
    void shouldLogSuccess() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .attemptCount(1)
                .build();

        service.logSuccess(job, "Success", 50L);
        verify(logRepo).save(any(JobExecutionLog.class));
    }

    @Test
    void shouldApplyFailureWhenTheLeaseIsStillOverdueAtWriteTime() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok")
                .visibleAt(Instant.now().minusSeconds(120))
                .build();
        when(jobRepo.saveIfLeaseHeldOverdue(any(), eq("tok"), any())).thenReturn(true);

        String outcome = service.handleFailureIfLeaseHeldOverdue(job, "tok", Instant.now(), "lease expired", 0L);

        assertEquals("RETRY_SCHEDULED", outcome);
        assertEquals(JobStatus.RETRY_SCHEDULED, job.getStatus());
        verify(jobRepo, never()).saveIfLeaseHeld(any(), anyString());
    }

    @Test
    void shouldDiscardFailureWhenTheLeaseWasRenewedPastTheDeadline() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok")
                .visibleAt(Instant.now().plusSeconds(120))
                .build();
        when(jobRepo.saveIfLeaseHeldOverdue(any(), eq("tok"), any())).thenReturn(false);

        String outcome = service.handleFailureIfLeaseHeldOverdue(job, "tok", Instant.now(), "lease expired", 0L);

        assertNull(outcome, "a renewed lease must survive the reaper's write");
        verify(logRepo, never()).save(any(JobExecutionLog.class));
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void shouldNotCheckTheDeadlineOnTheUnfencedPath() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok")
                .visibleAt(Instant.now().plusSeconds(120))
                .build();
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok"))).thenReturn(true);

        String outcome = service.handleFailureIfLeaseHeld(job, "tok", "handler failed", 0L);

        assertEquals("RETRY_SCHEDULED", outcome,
                "the executor path must not reject a lease that is merely close to its deadline");
        verify(jobRepo, never()).saveIfLeaseHeldOverdue(any(), anyString(), any());
    }
}
