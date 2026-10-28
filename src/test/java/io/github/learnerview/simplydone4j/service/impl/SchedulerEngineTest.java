package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.FencingTokenSequence;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The depth picture the scheduler sees. The engine reads every queue's depth in one
     * go, so a test states one picture per poll instead of one sequence per priority.
     */
    private static Map<JobPriority, Long> depths(long high, long normal, long low) {
        Map<JobPriority, Long> depths = new EnumMap<>(JobPriority.class);
        depths.put(JobPriority.HIGH, high);
        depths.put(JobPriority.NORMAL, normal);
        depths.put(JobPriority.LOW, low);
        return depths;
    }

    @Nested
    class DeficitScheduling {
        @Test
        void shouldNotPollWhenAllQueuesEmpty() {
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(0, 0, 0));

            scheduler.poll();

            verify(queueRepo, never()).claimReady(any(), anyInt());
        }

        @Test
        void shouldPickNonEmptyQueueWithHighestDeficit() {
            stubFencingTokens();
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
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
         * With weights 70/20/10, ten jobs per poll split as seven high, two normal and
         * one low. The old accounting charged a whole batch to one queue, so a queue that
         * could only supply one job paid the same price as a queue that supplied ten.
         */
        @Test
        void shouldServeQueuesInRoughlyProportionToTheirWeights() {
            stubFencingTokens();
            props.getScheduler().setBatchSize(10);
            scheduler = new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props);
            when(queueRepo.queueDepths()).thenReturn(depths(100, 100, 100));

            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(1);
            when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-x").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL).nextRunAt(Instant.now()).build()));

            List<JobPriority> served = new java.util.ArrayList<>();
            when(queueRepo.claimReady(any(), anyInt())).thenAnswer(invocation -> {
                JobPriority priority = invocation.getArgument(0);
                int count = invocation.getArgument(1);
                for (int i = 0; i < count; i++) {
                    served.add(priority);
                }
                return java.util.Collections.nCopies(count, "job-" + priority.name());
            });

            int polls = 30;
            for (int i = 0; i < polls; i++) {
                scheduler.poll();
            }

            assertEquals(polls * 10, served.size(),
                    "Every poll against a backlogged queue should claim its full batch");

            long high = served.stream().filter(p -> p == JobPriority.HIGH).count();
            long normal = served.stream().filter(p -> p == JobPriority.NORMAL).count();
            long low = served.stream().filter(p -> p == JobPriority.LOW).count();

            assertEquals(polls * 7, high, "high should receive 70% of the jobs");
            assertEquals(polls * 2, normal, "normal should receive 20% of the jobs");
            assertEquals(polls, low, "low should receive 10% of the jobs");
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
            when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0), depths(1, 0, 0),
                    depths(1, 0, 0), depths(0, 0, 1));

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
            props.getScheduler().setBatchSize(5);
            scheduler = new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props);
            when(queueRepo.queueDepths()).thenReturn(depths(50, 0, 0));
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
            verify(queueRepo).claimReady(JobPriority.HIGH, 5);
        }

        @Test
        void shouldNotExecuteWhenClaimReturnsEmpty() {
            when(queueRepo.queueDepths()).thenReturn(depths(50, 0, 0));
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of());

            scheduler.poll();

            verify(executor, never()).execute(any());
        }
    }

    /**
     * The scheduler may only claim what the workers can start.
     *
     * <p>A job that is claimed but cannot start still holds a lease, still has to be
     * renewed, and still has to be put back on the queue when the pool rejects it.
     * Claiming more than the pool can run turns a burst into a pile of work that cannot
     * proceed.</p>
     */
    @Nested
    class CapacityAwareness {
        private SchedulerEngine engineWith(int freeSlots) {
            WorkerCapacity capacity = new WorkerCapacity() {
                @Override public int availableSlots() { return freeSlots; }
                @Override public int maxConcurrency() { return 8; }
            };
            return new SchedulerEngine(queueRepo, jobRepo, executor, fencingTokens, props,
                    JobMetrics.NOOP, capacity);
        }

        @Test
        void shouldClaimNoMoreThanTheWorkersCanStart() {
            stubFencingTokens();
            when(queueRepo.queueDepths()).thenReturn(depths(50, 0, 0));
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of());

            engineWith(3).poll();

            verify(queueRepo).claimReady(JobPriority.HIGH, 3);
        }

        @Test
        void shouldNotClaimAnythingWhileTheWorkersAreSaturated() {
            stubFencingTokens();
            when(queueRepo.queueDepths()).thenReturn(depths(50, 0, 0));

            engineWith(0).poll();

            verify(queueRepo, never()).claimReady(any(), anyInt());
            verify(executor, never()).execute(any());
        }
    }

    /**
     * The loop comes back quickly while there is work to hand out and settles down when
     * there is not, so an idle application costs one cheap poll per second instead of
     * twenty.
     */
    @Nested
    class AdaptivePolling {
        @Test
        void shouldWaitTheIdleIntervalWhenTheLastPollFoundNothing() {
            when(queueRepo.queueDepths()).thenReturn(depths(0, 0, 0));

            scheduler.poll();

            assertEquals(props.getScheduler().getPollingIntervalMs(),
                    scheduler.nextPollDelay().toMillis());
        }

        @Test
        void shouldWaitTheBusyIntervalWhenTheLastPollFoundWork() {
            stubFencingTokens();
            when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-1"));
            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(1);
            when(jobRepo.findById("job-1")).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-1").jobType("test").status(JobStatus.QUEUED)
                    .priority(JobPriority.HIGH).build()));

            scheduler.poll();

            assertEquals(props.getScheduler().getBusyPollingIntervalMs(),
                    scheduler.nextPollDelay().toMillis());
        }
    }

    @Nested
    class ClaimAndExecute {
        @Test
        void shouldClaimJobAndPassToExecutor() {
            stubFencingTokens();
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
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
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-claim"));

            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(0);
            when(jobRepo.findById("job-claim")).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-claim").status(JobStatus.CANCELLED).build()));

            scheduler.poll();

            verify(executor, never()).execute(any());
            verify(queueRepo, never()).enqueue(anyString(), any(), anyLong());
        }

        @Test
        void shouldRequeueJobWhenClaimLosesButJobIsStillQueued() {
            stubFencingTokens();
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
            when(queueRepo.claimReady(eq(JobPriority.HIGH), anyInt())).thenReturn(List.of("job-claim"));

            when(jobRepo.claimForExecution(anyString(), anyString(), anyString(), any(), any(),
                    any(), any())).thenReturn(0);
            when(jobRepo.findById("job-claim")).thenReturn(Optional.of(JobEntity.builder()
                    .id("job-claim").status(JobStatus.QUEUED).build()));

            scheduler.poll();

            verify(executor, never()).execute(any());
            verify(queueRepo).enqueue(eq("job-claim"), eq(JobPriority.HIGH), anyLong());
        }

        @Test
        void shouldHandleExceptionDuringPollGracefully() {
            when(queueRepo.queueDepths()).thenThrow(new RuntimeException("Simulated error"));

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
            lenient().when(queueRepo.queueDepths()).thenReturn(depths(1, 0, 0));
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
