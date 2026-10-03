package io.github.learnerview.simplydone4j.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Two promoters must not be able to promote the same retry into a live lease.
 *
 * <p>Needs a real Redis: the hazard is a whole-record overwrite of a hash that another
 * client has since rewritten, and only the real store shows which fields actually survived.
 */
class RetryPromotionRaceRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobRepository jobRepo;
    private RedisQueueRepository queueRepo;
    private SimplyDoneProperties props;

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
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show which "
                        + "fields of an overwritten job hash actually survived.");
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
        props = new SimplyDoneProperties();
        jobRepo = new RedisJobRepository(redis, jobMapper(), props);
        queueRepo = new RedisQueueRepository(redis, props);
    }

    private void seedRetryScheduledJob(String id) {
        JobEntity job = JobEntity.builder()
                .id(id).jobType("test").producer("p")
                .status(JobStatus.RETRY_SCHEDULED)
                .priority(JobPriority.NORMAL)
                .maxAttempts(3)
                .attemptCount(1)
                .payload("{}")
                .nextRunAt(Instant.now().minusSeconds(5))
                .createdAt(Instant.now().minusSeconds(60))
                .updatedAt(Instant.now())
                .build();
        jobRepo.save(job);
    }

    @Test
    @DisplayName("a second promoter must not overwrite a job another instance already claimed")
    void secondPromoterMustNotClobberAClaimedJob() {
        seedRetryScheduledJob("job-1");

        // Both instances list the same due retry -- this is the snapshot the promoter works
        // from, so both hold an identical stale copy.
        List<JobEntity> instanceAView = jobRepo.findReadyToRun(JobStatus.RETRY_SCHEDULED,
                Instant.now(), 100);
        List<JobEntity> instanceBView = jobRepo.findReadyToRun(JobStatus.RETRY_SCHEDULED,
                Instant.now(), 100);
        assertEquals(1, instanceAView.size(), "precondition: both instances see the due retry");
        assertEquals(1, instanceBView.size(), "precondition: both instances see the due retry");

        WorkerMaintenanceServiceImpl instanceA =
                new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, nullRetryService(), props);
        instanceA.promoteRetries();

        // The scheduler claims it, exactly as SchedulerEngine does: claimReady takes the id
        // off the priority ZSET, then claimForExecution leases it.
        assertEquals(List.of("job-1"), queueRepo.claimReady(JobPriority.NORMAL, 10),
                "precondition: the promoted job is on the queue");
        Instant now = Instant.now();
        String leaseToken = "0000000000000000042";
        assertEquals(1, jobRepo.claimForExecution("job-1", leaseToken, "worker-a",
                        now.plusSeconds(30), now, JobStatus.QUEUED, JobStatus.RUNNING),
                "precondition: the promoted job was claimed and is now RUNNING");

        // Instance B now promotes its stale copy.
        WorkerMaintenanceServiceImpl instanceB =
                new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, nullRetryService(), props);
        instanceB.promoteRetries();

        JobEntity after = jobRepo.findById("job-1").orElseThrow();
        assertEquals(JobStatus.RUNNING, after.getStatus(),
                "instance B wrote its stale copy back over a job that is now RUNNING, so the "
                        + "job is queued again while attempt 1 is still executing");
        assertEquals(leaseToken, after.getLeaseToken(),
                "the live worker's lease token was deleted by the stale write, so its outcome "
                        + "can never be recorded and the lease reaper can no longer recover it");
        assertEquals(1L, jobRepo.countByStatus(JobStatus.RUNNING),
                "the job must stay in the RUNNING index or an expired lease is never reaped");
        assertEquals(0L, queueRepo.queueSize(JobPriority.NORMAL),
                "a running job must not also be sitting on the priority queue waiting to run "
                        + "again at the same time");
    }

    private RetryService nullRetryService() {
        // promoteRetries never touches the retry service; it is not part of this path.
        return new RetryService() {
            @Override
            public String handleFailure(JobEntity job, String errorMessage, long durationMs) {
                throw new UnsupportedOperationException("not on the promotion path");
            }

            @Override
            public String handleFailureIfLeaseHeld(JobEntity job, String expectedLeaseToken,
                                                  String errorMessage, long durationMs) {
                throw new UnsupportedOperationException("not on the promotion path");
            }

            @Override
            public void logSuccess(JobEntity job, String message, long durationMs) {
                throw new UnsupportedOperationException("not on the promotion path");
            }
        };
    }

    @Test
    @DisplayName("promotion enqueues the retry so the scheduler can pick it up")
    void promotionStillEnqueuesNormally() {
        seedRetryScheduledJob("job-2");

        new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, nullRetryService(), props)
                .promoteRetries();

        JobEntity after = jobRepo.findById("job-2").orElseThrow();
        assertNotNull(after);
        assertEquals(JobStatus.QUEUED, after.getStatus(), "the retry must be promoted");
        assertEquals(1L, queueRepo.queueSize(JobPriority.NORMAL),
                "a promoted retry that is not enqueued is stranded: nothing scans QUEUED status, "
                        + "so it would never run again");
    }
}