package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the crash window between taking a job off the ready queue and claiming it.
 *
 * <p>The scheduler removes a job from the priority ZSET and then transitions it to
 * {@code RUNNING} in a separate write. A process that dies between the two leaves a job
 * that still reads {@code QUEUED} but has no queue entry, so nothing ever runs it. The
 * reconciliation pass is the recovery path; these tests use a real Redis because the
 * hazard is about what actually survives in the store.</p>
 */
class QueueReconcilerRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobRepository jobRepo;
    private RedisQueueRepository queueRepo;
    private WorkerMaintenanceServiceImpl maintenance;

    @BeforeAll
    static void connectToRedis() {
        boolean reachable;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(HOST, PORT));
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            connectionFactory = factory;
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "Needs a real Redis at " + HOST + ":" + PORT
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show whether "
                        + "a queue entry actually survived.");
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        SimplyDoneProperties props = new SimplyDoneProperties();
        jobRepo = new RedisJobRepository(redis, props);
        queueRepo = new RedisQueueRepository(redis, props);
        maintenance = new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, null);
    }

    private void seedQueuedJob(String id, Instant nextRunAt) {
        JobEntity job = JobEntity.builder()
                .id(id).jobType("test").producer("p")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .maxAttempts(3)
                .payload("{}")
                .nextRunAt(nextRunAt)
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now())
                .build();
        jobRepo.save(job);
        queueRepo.enqueue(id, JobPriority.NORMAL, nextRunAt.toEpochMilli());
    }

    @Test
    @DisplayName("a job popped from the queue but never claimed is put back by reconciliation")
    void poppedButNotClaimedJobIsRecovered() {
        Instant due = Instant.now().minusSeconds(5);
        seedQueuedJob("job-1", due);

        // This is the crash: claimReady removed the entry, and the process died before
        // claimForExecution could move the job to RUNNING.
        assertEquals(List.of("job-1"), queueRepo.claimReady(JobPriority.NORMAL, 10));
        assertFalse(queueRepo.contains("job-1", JobPriority.NORMAL),
                "precondition: the queue entry is gone while the job still reads QUEUED");
        assertEquals(JobStatus.QUEUED, jobRepo.findById("job-1").orElseThrow().getStatus());

        int restored = maintenance.reconcileQueuedJobs();

        assertEquals(1, restored);
        assertTrue(queueRepo.contains("job-1", JobPriority.NORMAL));
        assertEquals(List.of("job-1"), queueRepo.claimReady(JobPriority.NORMAL, 10),
                "the reconciled job must be claimable again");
    }

    @Test
    @DisplayName("reconciliation is a no-op for a job that still has its queue entry")
    void healthyQueuedJobIsLeftAlone() {
        seedQueuedJob("job-2", Instant.now().minusSeconds(5));

        int restored = maintenance.reconcileQueuedJobs();

        assertEquals(0, restored);
        assertEquals(1L, queueRepo.queueSize(JobPriority.NORMAL),
                "the pass must not add a second member for the same job");
    }

    @Test
    @DisplayName("a future-scheduled job whose entry vanished is restored without running early")
    void futureJobIsRestoredWithItsScheduledScore() {
        Instant future = Instant.now().plusSeconds(3600);
        seedQueuedJob("job-3", future);
        queueRepo.claimReady(JobPriority.NORMAL, 10); // not due, so this removes nothing
        queueRepo.remove("job-3", JobPriority.NORMAL);

        int restored = maintenance.reconcileQueuedJobs();

        assertEquals(1, restored);
        assertTrue(queueRepo.contains("job-3", JobPriority.NORMAL));
        assertEquals(List.of(), queueRepo.claimReady(JobPriority.NORMAL, 10),
                "a job scheduled for later must not become runnable just because it was re-enqueued");
    }

    @Test
    @DisplayName("reconciliation rotates past the first batch to a stranded job behind it")
    void strandedJobBeyondTheFirstBatchIsRecovered() {
        Instant due = Instant.now().minusSeconds(5);
        for (int i = 0; i < 201; i++) {
            seedQueuedJob(String.format("deep-%03d", i), due.plusMillis(i));
        }
        queueRepo.remove("deep-200", JobPriority.NORMAL);

        assertEquals(0, maintenance.reconcileQueuedJobs(),
                "the first pass must leave the healthy oldest batch alone");
        assertEquals(1, maintenance.reconcileQueuedJobs(),
                "the second pass must rotate to the stranded job beyond the first 200");
        assertTrue(queueRepo.contains("deep-200", JobPriority.NORMAL),
                "the stranded job must be back on the queue after the rotating pass");
    }
}
