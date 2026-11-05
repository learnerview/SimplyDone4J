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
            when(jobRepo.transitionStatusIfCurrent("retry-1",
                    JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)).thenReturn(true);
            when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong()))
                    .thenReturn(true);

            service.promoteRetries();

            verify(jobRepo).transitionStatusIfCurrent("retry-1",
                    JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED);
            verify(jobRepo, never()).save(any());
            verify(queueRepo).enqueueIfUnderCapacity(dueJob.getId(), dueJob.getPriority(),
                    dueJob.getNextRunAt().toEpochMilli(), props.getQueue().getMaxDepth());
        }

        @Test
        void shouldNotEnqueueWhenAnotherPromoterWonTheTransition() {
            JobEntity dueJob = JobEntity.builder()
                    .id("retry-race")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now().minusSeconds(5))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RETRY_SCHEDULED), any(Instant.class), eq(100)))
                    .thenReturn(List.of(dueJob));
            when(jobRepo.transitionStatusIfCurrent("retry-race",
                    JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)).thenReturn(false);

            service.promoteRetries();

            verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        }

        @Test
        void shouldRestoreRetryStateWhenQueueEnqueueFails() {
            JobEntity dueJob = JobEntity.builder()
                    .id("retry-enqueue-failure")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now().minusSeconds(5))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RETRY_SCHEDULED), any(Instant.class), eq(100)))
                    .thenReturn(List.of(dueJob));
            when(jobRepo.transitionStatusIfCurrent("retry-enqueue-failure",
                    JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)).thenReturn(true);
            doThrow(new RuntimeException("redis unavailable"))
                    .when(queueRepo).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
            when(jobRepo.transitionStatusIfCurrent("retry-enqueue-failure",
                    JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED)).thenReturn(true);

            service.promoteRetries();

            verify(jobRepo).transitionStatusIfCurrent("retry-enqueue-failure",
                    JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED);
        }

        @Test
        void shouldDeferRetryWhenQueueIsAtMaxDepth() {
            JobEntity dueJob = JobEntity.builder()
                    .id("retry-full")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now().minusSeconds(5))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RETRY_SCHEDULED), any(Instant.class), eq(100)))
                    .thenReturn(List.of(dueJob));
            when(jobRepo.transitionStatusIfCurrent("retry-full",
                    JobStatus.RETRY_SCHEDULED, JobStatus.QUEUED)).thenReturn(true);
            when(queueRepo.enqueueIfUnderCapacity(eq("retry-full"), eq(JobPriority.NORMAL),
                    anyLong(), eq(props.getQueue().getMaxDepth()))).thenReturn(false);
            when(jobRepo.transitionStatusIfCurrent("retry-full",
                    JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED)).thenReturn(true);

            service.promoteRetries();

            verify(jobRepo).transitionStatusIfCurrent("retry-full",
                    JobStatus.QUEUED, JobStatus.RETRY_SCHEDULED);
            verify(queueRepo).enqueueIfUnderCapacity(eq("retry-full"), eq(JobPriority.NORMAL),
                    anyLong(), eq(props.getQueue().getMaxDepth()));
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
            verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
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

            verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
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
                    .leaseToken("old-token")
                    .leaseOwner("old-worker")
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.RUNNING), any(Instant.class), eq(100)))
                    .thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-1")).thenReturn(Optional.of(expiredJob));

            service.recoverExpiredLeases();

            verify(retryService).handleFailureIfLeaseHeldOverdue(eq(expiredJob), eq("old-token"),
                    any(Instant.class), eq("Worker lease expired"), eq(0L));
        }

        @Test
        void shouldHandleNoExpiredLeases() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of());

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailureIfLeaseHeldOverdue(any(), anyString(), any(Instant.class), anyString(), anyLong());
        }

        @Test
        void shouldHandleMultipleExpiredLeases() {
            List<JobEntity> expiredJobs = List.of(
                    JobEntity.builder().id("exp-1").jobType("test").status(JobStatus.RUNNING).leaseToken("token-1").leaseOwner("worker-1").build(),
                    JobEntity.builder().id("exp-2").jobType("test").status(JobStatus.RUNNING).leaseToken("token-2").leaseOwner("worker-2").build(),
                    JobEntity.builder().id("exp-3").jobType("test").status(JobStatus.RUNNING).leaseToken("token-3").leaseOwner("worker-3").build()
            );

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(expiredJobs);
            when(jobRepo.findById("exp-1")).thenReturn(Optional.of(expiredJobs.get(0)));
            when(jobRepo.findById("exp-2")).thenReturn(Optional.of(expiredJobs.get(1)));
            when(jobRepo.findById("exp-3")).thenReturn(Optional.of(expiredJobs.get(2)));

            service.recoverExpiredLeases();

            verify(retryService, times(3)).handleFailureIfLeaseHeldOverdue(any(), anyString(), any(Instant.class), anyString(), anyLong());
        }

        @Test
        void shouldSkipWhenReReadStatusNotRunning() {
            JobEntity expiredJob = JobEntity.builder()
                    .id("expired-skip")
                    .jobType("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .leaseToken("old-token")
                    .leaseOwner("old-worker")
                    .build();
            JobEntity alreadyRecovered = JobEntity.builder()
                    .id("expired-skip")
                    .jobType("test")
                    .status(JobStatus.RETRY_SCHEDULED)
                    .priority(JobPriority.NORMAL)
                    .leaseToken("new-token")
                    .leaseOwner("new-worker")
                    .build();

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-skip")).thenReturn(Optional.of(alreadyRecovered));

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailureIfLeaseHeldOverdue(any(), anyString(), any(Instant.class), anyString(), anyLong());
        }

        @Test
        void shouldSkipWhenReReadReturnsNull() {
            JobEntity expiredJob = JobEntity.builder()
                    .id("expired-gone")
                    .jobType("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .leaseToken("some-token")
                    .leaseOwner("some-worker")
                    .build();

            when(jobRepo.findReadyToRun(any(), any(), anyInt())).thenReturn(List.of(expiredJob));
            when(jobRepo.findById("expired-gone")).thenReturn(Optional.empty());

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailureIfLeaseHeldOverdue(any(), anyString(), any(Instant.class), anyString(), anyLong());
        }

        @Test
        void shouldHandleExceptionDuringLeaseRecovery() {
            when(jobRepo.findReadyToRun(any(), any(), anyInt()))
                    .thenThrow(new RuntimeException("DB error"));

            service.recoverExpiredLeases();

            verify(retryService, never()).handleFailureIfLeaseHeldOverdue(any(), anyString(), any(Instant.class), anyString(), anyLong());
        }
    }

    @Nested
    class OrphanReconciliation {
        @Test
        void shouldRequeueAQueuedJobWithNoQueueEntry() {
            JobEntity orphan = JobEntity.builder()
                    .id("orphan-1")
                    .jobType("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH)
                    .nextRunAt(Instant.now().minusSeconds(30))
                    .updatedAt(Instant.now().minusSeconds(30))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.QUEUED), any(Instant.class), eq(200)))
                    .thenReturn(List.of(orphan));
            when(queueRepo.contains("orphan-1", JobPriority.HIGH)).thenReturn(false);
            when(queueRepo.enqueueIfUnderCapacity("orphan-1", JobPriority.HIGH,
                    orphan.getNextRunAt().toEpochMilli(), props.getQueue().getMaxDepth()))
                    .thenReturn(true);

            service.reconcileOrphanedQueuedJobs();

            verify(queueRepo).enqueueIfUnderCapacity("orphan-1", JobPriority.HIGH,
                    orphan.getNextRunAt().toEpochMilli(), props.getQueue().getMaxDepth());
        }

        @Test
        void shouldNotRequeueAJobThatStillHasAQueueEntry() {
            JobEntity queued = JobEntity.builder()
                    .id("queued-1")
                    .jobType("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now().minusSeconds(30))
                    .updatedAt(Instant.now().minusSeconds(30))
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.QUEUED), any(Instant.class), eq(200)))
                    .thenReturn(List.of(queued));
            when(queueRepo.contains("queued-1", JobPriority.NORMAL)).thenReturn(true);

            service.reconcileOrphanedQueuedJobs();

            verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        }

        @Test
        void shouldLeaveANewlyPersistedJobAloneDuringTheGracePeriod() {
            JobEntity fresh = JobEntity.builder()
                    .id("fresh-1")
                    .jobType("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .nextRunAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(jobRepo.findReadyToRun(eq(JobStatus.QUEUED), any(Instant.class), eq(200)))
                    .thenReturn(List.of(fresh));

            service.reconcileOrphanedQueuedJobs();

            verify(queueRepo, never()).contains(anyString(), any());
            verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        }
    }
}
