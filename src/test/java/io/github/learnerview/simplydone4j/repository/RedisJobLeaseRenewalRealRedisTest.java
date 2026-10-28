package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the lease renewal semantics that {@link RedisJobRepository} must uphold.
 *
 * <p>A renewal is only correct if two things move together: the deadline stored on the
 * job hash and the score of the job's member in the {@code RUNNING} status index. The
 * reaper selects reclaimable jobs with {@code ZRANGEBYSCORE} on that index, so a renewed
 * deadline that did not move the score leaves the job looking expired and the reaper
 * reclaims a job that is still running.</p>
 */
class RedisJobLeaseRenewalRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static final String RUNNING_INDEX = "simplydone4j:idx:status:running";

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

    private JobEntity runningJob(Instant visibleAt, String leaseToken) {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setJobType("test");
        job.setProducer("producer-1");
        job.setStatus(JobStatus.RUNNING);
        job.setPriority(JobPriority.NORMAL);
        job.setLeaseToken(leaseToken);
        job.setLeaseOwner("worker-1");
        job.setVisibleAt(visibleAt);
        job.setUpdatedAt(Instant.now());
        repository.save(job);
        return job;
    }

    @Test
    @DisplayName("a renewal moves the deadline and the index score together")
    void shouldMoveDeadlineAndIndexScoreTogether() {
        Instant originalDeadline = Instant.now().plusSeconds(30);
        runningJob(originalDeadline, "tok-1");

        Double originalScore = redis.opsForZSet().score(RUNNING_INDEX, jobId);
        assertEquals(originalDeadline.toEpochMilli(), originalScore.longValue(), "precondition");

        Instant newDeadline = Instant.now().plusSeconds(120);
        assertTrue(repository.extendLease(jobId, "tok-1", newDeadline));

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(newDeadline.toEpochMilli(), after.getVisibleAt().toEpochMilli(),
                "the stored deadline must move, otherwise the reaper reclaims a running job");

        Double newScore = redis.opsForZSet().score(RUNNING_INDEX, jobId);
        assertEquals(newDeadline.toEpochMilli(), newScore.longValue(),
                "the index score must move with the deadline; the reaper selects reclaimable "
                        + "jobs by score, so a stale score makes a running job look expired");
    }

    @Test
    @DisplayName("a renewal with a stale token is rejected and changes nothing")
    void shouldRejectRenewalWithStaleToken() {
        Instant originalDeadline = Instant.now().plusSeconds(30);
        runningJob(originalDeadline, "tok-1");

        Instant newDeadline = Instant.now().plusSeconds(120);
        assertFalse(repository.extendLease(jobId, "stale-token", newDeadline),
                "a worker that lost its lease must not extend somebody else's");

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(originalDeadline.toEpochMilli(), after.getVisibleAt().toEpochMilli());

        Double score = redis.opsForZSet().score(RUNNING_INDEX, jobId);
        assertEquals(originalDeadline.toEpochMilli(), score.longValue());
    }

    @Test
    @DisplayName("a renewed job is no longer selected as expired")
    void shouldNotBeSelectedAsExpiredAfterRenewal() {
        Instant expired = Instant.now().minusSeconds(1);
        runningJob(expired, "tok-1");

        List<JobEntity> before = repository.findReadyToRun(JobStatus.RUNNING, Instant.now(), 10);
        assertEquals(1, before.size(), "precondition: an expired lease is reclaimable");

        assertTrue(repository.extendLease(jobId, "tok-1", Instant.now().plusSeconds(60)));

        List<JobEntity> after = repository.findReadyToRun(JobStatus.RUNNING, Instant.now(), 10);
        assertTrue(after.isEmpty(),
                "a job whose lease was renewed must not be handed to the reaper; it is still "
                        + "running and reclaiming it runs the same work twice");
    }
}
