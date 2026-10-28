package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
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
    void shouldFailTerminallyWithoutSpendingAnAttempt() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("unknown")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();

        when(jobRepo.saveIfLeaseHeld(eq(job), eq("tok-1"))).thenReturn(true);

        String status = service.handleTerminalFailureIfLeaseHeld(job, "tok-1", JobErrorCode.UNKNOWN_JOB_TYPE,
                "No handler registered for jobType: unknown", 5L);

        assertEquals("FAILED", status);
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(0, job.getAttemptCount(),
                "a job that can never run must not burn the retry budget");
        assertNull(job.getLeaseToken());
        assertNull(job.getNextRunAt());
        assertNotNull(job.getCompletedAt());
        verify(jobRepo).saveIfLeaseHeld(job, "tok-1");
        verify(logRepo).save(any(JobExecutionLog.class));
    }

    @Test
    void shouldDiscardTerminalFailureWhenTheLeaseWasLost() {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .status(JobStatus.RUNNING)
                .attemptCount(0)
                .leaseToken("tok-1")
                .build();

        when(jobRepo.saveIfLeaseHeld(eq(job), eq("tok-1"))).thenReturn(false);

        String status = service.handleTerminalFailureIfLeaseHeld(job, "tok-1", JobErrorCode.HANDLER_THREW, "boom", 5L);

        assertNull(status, "a lost lease means another worker owns the outcome");
        verify(logRepo, never()).save(any(JobExecutionLog.class));
    }
}
