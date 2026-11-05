package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerEngineTest {

    @Mock QueueRepository queueRepo;
    @Mock JobRepository jobRepo;
    @Mock JobExecutorService executor;

    SimplyDoneProperties props;
    SchedulerEngine scheduler;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        scheduler = new SchedulerEngine(queueRepo, jobRepo, executor, props);
    }

    @Nested
    class DeficitScheduling {
        @Test
        void shouldNotPollWhenAllQueuesEmpty() {
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            scheduler.poll();

            verify(queueRepo, never()).claimReady(any(), anyInt());
        }

        @Test
        void shouldPickNonEmptyQueueWithHighestDeficit() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));

            JobEntity job = JobEntity.builder()
                    .id("job-1")
                    .jobType("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH)
                    .nextRunAt(Instant.now())
                    .build();
            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

            scheduler.poll();

            verify(queueRepo).claimReady(eq(JobPriority.HIGH), anyInt());
            verify(executor).execute(job);
        }

        @Test
        void shouldDistributeAcrossPrioritiesOverMultiplePolls() {
            lenient().when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(1L);
            when(queueRepo.claimReady(any(JobPriority.class), anyInt())).thenReturn(List.of("job-x"));

            JobEntity job = JobEntity.builder()
                    .id("job-x").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL).nextRunAt(Instant.now()).build();
            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            when(jobRepo.findById("job-x")).thenReturn(Optional.of(job));

            for (int i = 0; i < 10; i++) {
                scheduler.poll();
            }

            verify(executor, atLeast(5)).execute(any());
        }
    }

    @Nested
    class ClaimAndExecute {
        @Test
        void shouldClaimJobAndPassToExecutor() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-claim"));

            when(jobRepo.claimForExecution(eq("job-claim"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);

            JobEntity claimedJob = JobEntity.builder()
                    .id("job-claim").jobType("test").status(JobStatus.RUNNING)
                    .priority(JobPriority.HIGH).leaseToken("tok-1").leaseOwner("worker-x")
                    .build();
            when(jobRepo.findById("job-claim")).thenReturn(Optional.of(claimedJob));

            scheduler.poll();

            verify(executor).execute(claimedJob);
        }

        @Test
        void shouldNotExecuteWhenClaimFails() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-claim"));

            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(0);

            scheduler.poll();

            verify(executor, never()).execute(any());
        }

        @Test
        void shouldHandleExceptionDuringPollGracefully() {
            when(queueRepo.queueSize(any(JobPriority.class))).thenThrow(new RuntimeException("Simulated error"));

            assertDoesNotThrow(() -> scheduler.poll());
        }
    }

    @Nested
    class WorkerIdentity {
        @Test
        void shouldGenerateUniqueWorkerId() {
            SchedulerEngine s1 = new SchedulerEngine(queueRepo, jobRepo, executor, props);
            SchedulerEngine s2 = new SchedulerEngine(queueRepo, jobRepo, executor, props);

            assertNotNull(s1);
            assertNotNull(s2);
        }

        @Test
        void shouldAssignLeaseTokenDuringClaim() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-lease"));

            ArgumentCaptor<String> leaseTokenCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> workerIdCaptor = ArgumentCaptor.forClass(String.class);

            when(jobRepo.claimForExecution(eq("job-lease"), leaseTokenCaptor.capture(),
                    workerIdCaptor.capture(), any(), any(), any(), any())).thenReturn(1);

            JobEntity job = JobEntity.builder()
                    .id("job-lease").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH).build();
            when(jobRepo.findById("job-lease")).thenReturn(Optional.of(job));

            scheduler.poll();

            assertNotNull(leaseTokenCaptor.getValue());
            assertTrue(workerIdCaptor.getValue().startsWith("worker-"));
        }
    }

    @Nested
    class RecoveryAndCapacity {
        private SchedulerEngine with(WorkerCapacity capacity, JobSignal signal) {
            return new SchedulerEngine(queueRepo, jobRepo, executor, capacity, signal, props);
        }

        @Test
        void shouldNotTouchRedisWhenNoCapacityIsFree() {
            WorkerCapacity capacity = mock(WorkerCapacity.class);
            when(capacity.availablePermits()).thenReturn(0);

            assertEquals(0, with(capacity, mock(JobSignal.class)).pollOnce());
            verifyNoInteractions(queueRepo, jobRepo, executor);
        }

        @Test
        void shouldRestoreAJobWhenCapacityDisappearedAfterTheBatchClaim() {
            WorkerCapacity capacity = mock(WorkerCapacity.class);
            when(capacity.availablePermits()).thenReturn(2, 1);
            when(capacity.tryAcquire()).thenReturn(false);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            JobEntity queued = JobEntity.builder()
                    .id("job-1").status(JobStatus.QUEUED).priority(JobPriority.HIGH)
                    .nextRunAt(Instant.parse("2026-01-01T00:00:00Z")).build();
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued));

            with(capacity, mock(JobSignal.class)).pollOnce();

            verify(queueRepo).enqueue("job-1", JobPriority.HIGH,
                    Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
            verify(executor, never()).execute(any());
        }

        @Test
        void shouldRestoreAJobWhoseClaimLostTheRace() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            when(jobRepo.claimForExecution(eq("job-1"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(0);
            JobEntity queued = JobEntity.builder()
                    .id("job-1").status(JobStatus.QUEUED).priority(JobPriority.HIGH)
                    .nextRunAt(Instant.now()).build();
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued));

            assertEquals(0, with(capacity, mock(JobSignal.class)).pollOnce());

            verify(queueRepo).enqueue(eq("job-1"), eq(JobPriority.HIGH), anyLong());
            verifyNoInteractions(executor);
        }

        @Test
        void shouldNotRestoreAJobAnotherWorkerNowOwns() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            when(jobRepo.claimForExecution(eq("job-1"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(0);
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(
                    JobEntity.builder().id("job-1").status(JobStatus.RUNNING).build()));

            with(capacity, mock(JobSignal.class)).pollOnce();

            verify(queueRepo, never()).enqueue(anyString(), any(), anyLong());
        }

        @Test
        void shouldRequeueAJobWhoseDispatchThrows() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            ArgumentCaptor<String> leaseToken = ArgumentCaptor.forClass(String.class);
            when(jobRepo.claimForExecution(eq("job-1"), leaseToken.capture(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            JobEntity job = JobEntity.builder()
                    .id("job-1").jobType("test").status(JobStatus.RUNNING)
                    .priority(JobPriority.HIGH).build();
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
            when(jobRepo.saveIfLeaseHeld(same(job), anyString())).thenReturn(true);
            doThrow(new IllegalStateException("pool rejected")).when(executor).execute(job);

            assertEquals(0, with(capacity, mock(JobSignal.class)).pollOnce());

            verify(jobRepo).saveIfLeaseHeld(same(job), eq(leaseToken.getValue()));
            verify(queueRepo).enqueue(eq("job-1"), eq(JobPriority.HIGH), anyLong());
            assertEquals(4, capacity.availablePermits(), "a rejected dispatch must release its permit");
        }

        @Test
        void shouldNotDispatchAJobThatVanishedAfterTheClaim() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("ghost"));
            when(jobRepo.claimForExecution(eq("ghost"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            when(jobRepo.findById("ghost")).thenReturn(Optional.empty());

            with(capacity, mock(JobSignal.class)).pollOnce();

            verify(executor, never()).execute(any());
        }

        @Test
        void shouldSignalTheLoopAfterDispatchingWork() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            JobSignal signal = mock(JobSignal.class);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            when(jobRepo.claimForExecution(eq("job-1"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(
                    JobEntity.builder().id("job-1").jobType("test").status(JobStatus.RUNNING).build()));

            with(capacity, signal).pollOnce();

            verify(signal).signal();
        }

        @Test
        void shouldSwallowARestoreFailureRatherThanFailingThePoll() {
            WorkerCapacity capacity = mock(WorkerCapacity.class);
            when(capacity.availablePermits()).thenReturn(2, 1);
            when(capacity.tryAcquire()).thenReturn(false);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            when(jobRepo.findById("job-1")).thenThrow(new IllegalStateException("redis blip"));

            assertDoesNotThrow(() -> with(capacity, mock(JobSignal.class)).pollOnce());
            verify(queueRepo, never()).enqueue(anyString(), any(), anyLong());
        }

        @Test
        void shouldReleaseThePermitWhenRedisThrowsBetweenClaimAndDispatch() {
            WorkerCapacity capacity = new WorkerCapacity(4);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-x"));
            when(jobRepo.claimForExecution(eq("job-x"), anyString(), anyString(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("redis blip"));
            JobEntity queued = JobEntity.builder()
                    .id("job-x").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH).nextRunAt(Instant.now()).build();
            when(jobRepo.findById("job-x")).thenReturn(Optional.of(queued));

            assertEquals(0, with(capacity, mock(JobSignal.class)).pollOnce());

            assertEquals(4, capacity.availablePermits(),
                    "an exception after the permit was taken must not drain the pool");
            verify(queueRepo).enqueue(eq("job-x"), eq(JobPriority.HIGH), anyLong());
        }
    }

    @Nested
    class PrioritySelection {
        @Test
        void shouldSkipAHighPriorityQueueThatHoldsOnlyFutureJobs() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            // HIGH's only member is not due yet; the normal queue has work due now.
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of());
            when(queueRepo.claimReady(eq(JobPriority.NORMAL), anyInt())).thenReturn(List.of("job-normal"));
            JobEntity job = JobEntity.builder()
                    .id("job-normal").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL).nextRunAt(Instant.now()).build();
            when(jobRepo.claimForExecution(eq("job-normal"), anyString(), anyString(), any(), any(),
                    eq(JobStatus.QUEUED), eq(JobStatus.RUNNING))).thenReturn(1);
            when(jobRepo.findById("job-normal")).thenReturn(Optional.of(job));

            scheduler.poll();

            verify(executor).execute(job);
            verify(queueRepo, never()).claimReady(eq(JobPriority.LOW), anyInt());
        }
    }
}
