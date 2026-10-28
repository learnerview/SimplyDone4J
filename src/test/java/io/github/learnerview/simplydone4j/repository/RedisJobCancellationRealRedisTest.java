package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the cancellation semantics that {@link RedisJobRepository} must uphold.
 *
 * <p>Three things have to be true at once: a queued job that is cancelled never runs, a
 * running job that is cancelled is told to stop, and neither of them can be undone by a
 * worker that was already on the job. The third one is why the whole thing is one script
 * rather than a status flip followed by a save.</p>
 */
class RedisJobCancellationRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobRepository repository;
    private String jobId;


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
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show "
                        + "what the server actually stores.");
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
        repository = new RedisJobRepository(redis, new SimplyDoneProperties());
        jobId = UUID.randomUUID().toString();
    }

    private JobEntity job(JobStatus status) {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setJobType("test");
        job.setProducer("producer-1");
        job.setStatus(status);
        job.setPriority(JobPriority.NORMAL);
        job.setLeaseToken("tok-1");
        job.setLeaseOwner("worker-1");
        job.setUpdatedAt(Instant.now());
        repository.save(job);
        return job;
    }

    @Test
    @DisplayName("cancelling a queued job makes it terminal and unrunnable")
    void shouldCancelAQueuedJob() {
        job(JobStatus.QUEUED);

        assertEquals(CancelOutcome.CANCELLED, repository.requestCancellation(jobId, "operator"));

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.CANCELLED, after.getStatus());
        assertEquals(1L, repository.countByStatus(JobStatus.CANCELLED),
                "a cancelled job has to be counted as cancelled, or the status counts lie");
        assertEquals(0L, repository.countByStatus(JobStatus.QUEUED),
                "a cancelled job must leave the queue index, or it stays a member of a "
                        + "status it no longer has");
        assertNull(after.getLeaseToken());
        assertTrue(after.getResult().contains("operator"));
    }

    @Test
    @DisplayName("cancelling a running job records the request and leaves the state to the worker")
    void shouldRecordTheRequestForARunningJob() {
        job(JobStatus.RUNNING);

        assertEquals(CancelOutcome.REQUESTED, repository.requestCancellation(jobId, "operator"));

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.RUNNING, after.getStatus(),
                "a running job cannot be un-run; the worker stops it and writes the terminal state");
        assertTrue(repository.isCancellationRequested(jobId),
                "the worker polls this flag, so it has to be in the store");
        assertEquals("operator", after.getCancelledBy());
    }

    @Test
    @DisplayName("a job that has already finished cannot be cancelled")
    void shouldRefuseToCancelAFinishedJob() {
        job(JobStatus.SUCCESS);

        assertEquals(CancelOutcome.NOT_CANCELLABLE, repository.requestCancellation(jobId, "operator"));

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.SUCCESS, after.getStatus());
        assertFalse(repository.isCancellationRequested(jobId));
    }

    @Test
    @DisplayName("a missing job is reported as missing")
    void shouldReportAMissingJob() {
        assertEquals(CancelOutcome.NOT_FOUND, repository.requestCancellation("no-such-job", "operator"));
    }

    @Test
    @DisplayName("a cancelled job is not claimed by a worker")
    void shouldNotClaimACancelledJob() {
        job(JobStatus.QUEUED);
        repository.requestCancellation(jobId, "operator");

        int claimed = repository.claimForExecution(jobId, "tok-2", "worker-2",
                Instant.now().plusSeconds(30), Instant.now(), JobStatus.QUEUED, JobStatus.RUNNING);

        assertEquals(0, claimed, "a cancelled job must never run");
    }
}
