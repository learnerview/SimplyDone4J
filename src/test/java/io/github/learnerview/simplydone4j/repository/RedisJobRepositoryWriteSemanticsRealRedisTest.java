package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Redis write semantics that {@link RedisJobRepository} must uphold.
 *
 * <p>Every case here needs a real Redis: they are all about what the server actually
 * stores, which a mocked template cannot show.
 */
class RedisJobRepositoryWriteSemanticsRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobRepository repository;
    private String jobId;

    /**
     * Mirrors the mapper Spring Boot supplies. A bare {@code new ObjectMapper()} cannot
     * serialise {@code Instant}, which {@link JobEntity} uses for its timestamps.
     */
    private static ObjectMapper jobMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

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
        repository = new RedisJobRepository(redis, jobMapper(), new SimplyDoneProperties());
        jobId = UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("nulling a field must delete it, not leave the previous value")
    void shouldDeleteFieldsThatBecomeNull() {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setStatus(JobStatus.DLQ);
        job.setResult("Max retries exceeded");
        job.setLeaseToken("lease-abc");
        job.setLeaseOwner("worker-1");
        job.setCompletedAt(Instant.now());
        repository.save(job);

        Map<Object, Object> stored = redis.opsForHash().entries("simplydone4j:job:" + jobId);
        assertEquals("Max retries exceeded", stored.get("result"), "precondition");

        // This is exactly what DeadLetterServiceImpl.requeue does.
        job.setStatus(JobStatus.QUEUED);
        job.setResult(null);
        job.setLeaseToken(null);
        job.setLeaseOwner(null);
        job.setCompletedAt(null);
        repository.save(job);

        Map<Object, Object> after = redis.opsForHash().entries("simplydone4j:job:" + jobId);
        assertNull(after.get("result"),
                "result must be deleted; a stale 'Max retries exceeded' kept a requeued job "
                        + "looking dead while it was in fact queued and runnable");
        assertNull(after.get("leaseToken"),
                "a stale leaseToken lets a zombie worker from the dead run still match its "
                        + "fencing token and overwrite the requeued job");
        assertNull(after.get("leaseOwner"), "leaseOwner must be cleared with the lease");
        assertNull(after.get("completedAt"), "completedAt must be cleared with the lease");
    }

    @Test
    @DisplayName("requeueing must drop the retention TTL so the job is not deleted mid-flight")
    void shouldPersistAwayTtlWhenJobLeavesTerminalState() {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setResult("boom");
        repository.save(job);

        String key = "simplydone4j:job:" + jobId;
        assertTrue(redis.getExpire(key) > 0,
                "precondition: a terminal job is written with a retention TTL");

        // Requeue.
        job.setStatus(JobStatus.QUEUED);
        job.setResult(null);
        repository.save(job);

        assertEquals(-1L, redis.getExpire(key),
                "A requeued job is queued and waiting to run, so it must carry no expiry. "
                        + "Keeping the terminal TTL deletes it mid-flight and leaves a "
                        + "zombie member in the priority ZSET pointing at a missing job.");
    }

    @Test
    @DisplayName("requeueing must remove the job from the terminal status index")
    void shouldRemoveFromTerminalIndexOnRequeue() {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setStatus(JobStatus.DLQ);
        job.setResult("Max retries exceeded");
        repository.save(job);

        String dlqIndex = "simplydone4j:idx:status:dlq";
        assertEquals(1L, redis.opsForZSet().zCard(dlqIndex), "precondition");

        job.setStatus(JobStatus.QUEUED);
        job.setResult(null);
        repository.save(job);

        assertEquals(0L, redis.opsForZSet().zCard(dlqIndex),
                "countByStatus(DLQ) must fall when a job is requeued, otherwise health stays "
                        + "DOWN forever and requeueAll keeps re-selecting requeued jobs");
        assertEquals(1L, redis.opsForZSet().zCard("simplydone4j:idx:status:queued"),
                "precondition: the requeued job is now queued");
    }

    @Test
    @DisplayName("payload is dropped on terminal writes when configured")
    void shouldDropPayloadOnTerminalWrite() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRetention().setClearPayloadOnCompletion(true);
        RedisJobRepository repo = new RedisJobRepository(redis, jobMapper(), props);

        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setStatus(JobStatus.RUNNING);
        job.setPayload("secret");
        repo.save(job);
        assertEquals("secret", redis.opsForHash().get("simplydone4j:job:" + jobId, "payload"));

        job.setStatus(JobStatus.SUCCESS);
        job.setPayload("secret");
        repo.save(job);

assertNull(redis.opsForHash().get("simplydone4j:job:" + jobId, "payload"),
                "a completed job must not keep its payload when retention says to clear it");
        assertFalse(redis.opsForHash().entries("simplydone4j:job:" + jobId).isEmpty(),
                "the record itself must survive; only the payload is dropped");
    }

    @Test
    @DisplayName("a zero retention window must not delete the terminal status index")
    void shouldNotExpireTerminalIndexAwayWhenRetentionWindowIsZero() {
        // Pins the raw Redis semantic this relies on: EXPIRE key 0 DELETES the key. It is
        // asserted rather than assumed so a future server change would fail here instead of
        // silently invalidating the reasoning below.
        String probe = "simplydone4j:expire-zero-probe";
        redis.opsForZSet().add(probe, "m", 1.0d);
        assertTrue(redis.expire(probe, Duration.ZERO),
                "precondition: the probe key exists and accepts an expiry");
        assertFalse(redis.hasKey(probe),
                "precondition: EXPIRE with a zero TTL deletes the key outright");

        // ttl-days 0 + ttl-hours 0 is the smallest retention window an operator can ask
        // for. RedisJobRepository.applyRetention clamps it to a 1h floor for the job hash;
        // applyIndexWrites must apply the identical clamp.
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setTtlDays(0);
        props.setTtlHours(0);
        RedisJobRepository repo = new RedisJobRepository(redis, jobMapper(), props);

        JobEntity dead = new JobEntity();
        dead.setId(jobId);
        dead.setStatus(JobStatus.DLQ);
        dead.setResult("Max retries exceeded");
        repo.save(dead);

        assertEquals(1L, repo.countByStatus(JobStatus.DLQ),
                "A terminal job that is still inside its own retention window must be "
                        + "countable. An expiry of 0 on the index key deletes the whole "
                        + "ZSET, so countByStatus reads 0, health never reports the DLQ, and "
                        + "DeadLetterService.listDeadLettered cannot find the job to requeue.");

        assertTrue(redis.getExpire("simplydone4j:idx:status:dlq") > 0,
                "the index key must carry the same positive retention TTL as the job it "
                        + "points at, so members disappear exactly when their jobs do");
    }

    @Test
    @DisplayName("a zero retention window must not delete the execution log just written")
    void shouldNotExpireExecutionLogAwayWhenRetentionWindowIsZero() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setTtlDays(0);
        props.setTtlHours(0);
        props.getRetention().setStoreExecutionLogs(true);
        RedisJobExecutionLogRepository repo = new RedisJobExecutionLogRepository(redis, jobMapper(), props);

        repo.save(JobExecutionLog.builder()
                .jobId(jobId)
                .attempt(0)
                .status("FAILED")
                .message("boom")
                .executedAt(Instant.now())
                .build());

        assertEquals(1, repo.findByJobIdOrderByAttemptAsc(jobId).size(),
                "an EXPIRE of 0 issued right after the LPUSH deletes the list it just wrote, "
                        + "so every execution log vanishes while execution-log storage is "
                        + "switched on");
    }
}
