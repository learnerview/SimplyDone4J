package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerMaintenanceServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;

    SimplyDoneProperties props;
    WorkerMaintenanceServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        service = new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, props);
    }

    @Nested
    class RetryPromotion {
        @Test
        void shouldPromoteDueRetriesToQueued() {
            JobEntity dueJob = JobEntity.builder()
                    .id("retry-1")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now().minusSeconds(5))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RETRY_SCHEDULED), any(Instant.class), eq(100)))
                    .thenReturn(List.of(dueJob));

            service.promoteRetries();

            verify(jobRepo).save(dueJob);
            verify(queueRepo).enqueue(dueJob.getId(), dueJob.getPriority(),
                    dueJob.getNextRunAt().toEpochMilli());
        }

        @Test
        void shouldNotPromoteFutureRetries() {
            JobEntity futureJob = JobEntity.builder()
                    .id("retry-future")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.LOW)
                    .nextRunAt(Instant.now().plusSeconds(60))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RETRY_SCHEDULED), any(Instant.class), eq(100)))
                    .thenReturn(List.of());

            service.promoteRetries();

            verify(jobRepo, never()).save(any());
            verify(queueRepo, never()).enqueue(anyString(), any(), anyLong());
        }

        @Test
        void shouldHandleEmptyPromotionList() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of());

            service.promoteRetries();

            verify(jobRepo, never()).save(any());
        }

        @Test
        void shouldHandleExceptionDuringPromotion() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            service.promoteRetries();

            verify(queueRepo, never()).enqueue(anyString(), any(), anyLong());
        }
    }

    @Nested
    class LeaseRecovery {
        @Test
        void shouldRecoverExpiredLeases() {
            JobEntity expiredJob = JobEntity.builder()
                    .id("expired-1")
                    .jobType("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.HIGH)
                    .visibleAt(Instant.now().minusSeconds(60))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RUNNING), any(Instant.class), eq(100)))
                    .thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-1")).thenReturn(Optional.of(expiredJob));

            service.recoverExpiredLeases();

            verify(retryService).handleFailure(expiredJob, "Worker lease expired", 0L);
        }

        @Test
        void shouldHandleNoExpiredLeases() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of());

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
        }

        @Test
        void shouldHandleMultipleExpiredLeases() {
            List<JobEntity> expiredJobs = List.of(
                    JobEntity.builder().id("exp-1").jobType("test").status(JobStatus.RUNNING).build(),
                    JobEntity.builder().id("exp-2").jobType("test").status(JobStatus.RUNNING).build(),
                    JobEntity.builder().id("exp-3").jobType("test").status(JobStatus.RUNNING).build()
            );

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(expiredJobs);
            when(jobRepo.findById("exp-1")).thenReturn(Optional.of(expiredJobs.get(0)));
            when(jobRepo.findById("exp-2")).thenReturn(Optional.of(expiredJobs.get(1)));
            when(jobRepo.findById("exp-3")).thenReturn(Optional.of(expiredJobs.get(2)));

            service.recoverExpiredLeases();

            verify(retryService, times(3)).handleFailure(any(), anyString(), anyLong());
        }

        @Test
        void shouldSkipWhenReReadStatusNotRunning() {
            JobEntity expiredJob = JobEntity.builder()
                    .id("expired-skip")
                    .jobType("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .build();
            JobEntity alreadyRecovered = JobEntity.builder()
                    .id("expired-skip")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .build();

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-skip")).thenReturn(Optional.of(alreadyRecovered));

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
        }

        @Test
        void shouldSkipWhenReReadReturnsNull() {
            JobEntity expiredJob = JobEntity.builder()
                    .id("expired-gone")
                    .jobType("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .build();

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-gone")).thenReturn(Optional.empty());

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
        }

        @Test
        void shouldHandleExceptionDuringLeaseRecovery() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
        }
    }
}
