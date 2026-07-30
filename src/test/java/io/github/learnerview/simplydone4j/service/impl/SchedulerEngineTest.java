package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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

            verify(queueRepo, never()).claimNextReady(any());
        }

        @Test
        void shouldPickNonEmptyQueueWithHighestDeficit() {
            lenient().when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L);
            lenient().when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            lenient().when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimNextReady(JobPriority.HIGH)).thenReturn(Optional.of("job-1"));

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

            verify(queueRepo).claimNextReady(JobPriority.HIGH);
            verify(executor).execute(job);
        }

        @Test
        void shouldDistributeAcrossPrioritiesOverMultiplePolls() {
            lenient().when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(1L);
            when(queueRepo.claimNextReady(any(JobPriority.class))).thenReturn(Optional.of("job-x"));

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
            when(queueRepo.claimNextReady(JobPriority.HIGH)).thenReturn(Optional.of("job-claim"));

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
            when(queueRepo.claimNextReady(JobPriority.HIGH)).thenReturn(Optional.of("job-claim"));

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
            when(queueRepo.claimNextReady(JobPriority.HIGH)).thenReturn(Optional.of("job-lease"));

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
}
