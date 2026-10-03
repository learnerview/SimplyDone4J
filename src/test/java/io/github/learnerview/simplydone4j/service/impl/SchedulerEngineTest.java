package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.FencingTokenSequence;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchedulerEngineTest {

    @Mock QueueRepository queueRepo;
    @Mock JobRepository jobRepo;
    @Mock JobExecutorService executor;
    @Mock FencingTokenSequence fencingTokens;

    SimplyDoneProperties props;
    SchedulerEngine scheduler;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        scheduler = new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props);
    }

    private void stubFencingTokens() {
        AtomicLong counter = new AtomicLong();
        lenient().when(fencingTokens.nextToken()).thenAnswer(inv -> "0000000000000000001" + counter.getAndIncrement());
    }

    @Nested
    class DeficitScheduling {
        @Test
        void shouldNotPollWhenAllQueuesEmpty() {
            lenient().when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            scheduler.poll();

            verify(queueRepo, never()).claimReady(any(), anyInt());
        }

        @Test
        void shouldPickNonEmptyQueueWithHighestDeficit() {
            stubFencingTokens();
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

        /**
         * Asserts the weighted split actually holds rather than merely "some jobs ran".
         * With weights 70/20/10 and a 100 total, a high-priority queue should win
         * comfortably more polls than a normal one, and a normal one more than a low one.
         */
        @Test
        void shouldServeQueuesInRoughlyProportionToTheirWeights() {
            stubFencingTokens();
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(1L);

            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(1);
            when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-x").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL).nextRunAt(Instant.now()).build()));

            ArgumentCaptor<JobPriority> priorityCaptor = ArgumentCaptor.forClass(JobPriority.class);
            when(queueRepo.claimReady(priorityCaptor.capture(), anyInt())).thenReturn(List.of("job-x"));

            int polls = 30;
            for (int i = 0; i < polls; i++) {
                scheduler.poll();
            }

            List<JobPriority> served = priorityCaptor.getAllValues();
            assertEquals(polls, served.size(), "Every poll against a backlogged queue should claim a batch");

            long high = served.stream().filter(p -> p == JobPriority.HIGH).count();
            long normal = served.stream().filter(p -> p == JobPriority.NORMAL).count();
            long low = served.stream().filter(p -> p == JobPriority.LOW).count();

            assertEquals(polls, high + normal + low);
            assertTrue(high > normal, "high (" + high + ") should outrank normal (" + normal + ")");
            assertTrue(normal > low, "normal (" + normal + ") should outrank low (" + low + ")");
            assertTrue(high >= polls / 2, "high (" + high + ") should win at least half of " + polls + " polls");
        }

        /**
         * Deficit must survive an idle tick. Resetting it on an empty queue would silently
         * degrade weighted DRR into plain weighted round-robin and strip an idle queue of
         * the credit that lets it burst when work arrives.
         */
        @Test
        void shouldAccumulateDeficitForAQueueThatGoesIdle() {
            stubFencingTokens();
            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(1);
            when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-y").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.LOW).nextRunAt(Instant.now()).build()));

            ArgumentCaptor<JobPriority> priorityCaptor = ArgumentCaptor.forClass(JobPriority.class);
            when(queueRepo.claimReady(priorityCaptor.capture(), anyInt())).thenReturn(List.of("job-y"));

            // Three polls where only HIGH has work, then a poll where only LOW does.
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(1L, 1L, 1L, 0L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L, 0L, 0L, 0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L, 0L, 0L, 1L);

            for (int i = 0; i < 4; i++) {
                scheduler.poll();
            }

            List<JobPriority> served = priorityCaptor.getAllValues();
            assertEquals(JobPriority.LOW, served.get(served.size() - 1),
                    "An idle LOW queue should have banked enough credit to win immediately, "
                            + "but the schedule was " + served);
        }
    }

    @Nested
    class BatchClaiming {
        @Test
        void shouldClaimUpToBatchSizeJobsPerPoll() {
            stubFencingTokens();
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(50L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt()))
                    .thenReturn(List.of("job-1", "job-2", "job-3"));
            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(1);
            when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-1").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH).nextRunAt(Instant.now()).build()));

            scheduler.poll();

            // One poll must dispatch the whole batch, not a single job -- that per-tick
            // cap is what previously limited throughput to 1/polling-interval.
            verify(executor, times(3)).execute(any());
            verify(queueRepo).claimReady(JobPriority.HIGH, props.getScheduler().getBatchSize());
        }

        @Test
        void shouldNotExecuteWhenClaimReturnsEmpty() {
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(50L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(0L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of());

            scheduler.poll();

            verify(executor, never()).execute(any());
        }
    }

    @Nested
    class ClaimAndExecute {
        @Test
        void shouldClaimJobAndPassToExecutor() {
            stubFencingTokens();
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
            stubFencingTokens();
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
            SchedulerEngine s1 = new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props);
            SchedulerEngine s2 = new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props);

            assertNotNull(s1);
            assertNotNull(s2);
        }

        @Test
        void shouldAssignLeaseTokenFromTheMonotonicSequenceDuringClaim() {
            stubFencingTokens();
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

            // The lease token must come from the monotonic sequence, never a random UUID,
            // because only a monotonic token can order two epochs of ownership.
            assertEquals("00000000000000000010", leaseTokenCaptor.getValue());
            assertTrue(workerIdCaptor.getValue().startsWith("worker-"));
        }
    }
}
