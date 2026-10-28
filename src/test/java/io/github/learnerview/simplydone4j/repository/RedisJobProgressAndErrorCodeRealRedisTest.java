package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
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
 * Pins how a job's progress and failure code survive the round trip through Redis.
 *
 * <p>The progress report and the error code are only useful if a reader can get them back
 * out of the store. A report that is written but never readable is a write the operator
 * pays for and cannot see, and a code that is dropped on the way to the hash turns every
 * failure back into "something went wrong".</p>
 */
class RedisJobProgressAndErrorCodeRealRedisTest {

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

    private JobEntity runningJob() {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setJobType("test");
        job.setProducer("producer-1");
        job.setStatus(JobStatus.RUNNING);
        job.setPriority(JobPriority.NORMAL);
        job.setLeaseToken("tok-1");
        job.setLeaseOwner("worker-1");
        job.setVisibleAt(Instant.now().plusSeconds(30));
        job.setUpdatedAt(Instant.now());
        repository.save(job);
        return job;
    }

    @Test
    @DisplayName("a progress report is readable while the job is running")
    void shouldStoreAndReadBackAProgressReport() {
        runningJob();

        assertTrue(repository.updateProgress(jobId, 42.5, "step two"));

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(42.5, after.getProgress(), 0.001, "the reported percentage must survive the store");
        assertEquals("step two", after.getProgressMessage());
    }

    @Test
    @DisplayName("the failure code survives the store")
    void shouldStoreAndReadBackTheErrorCode() {
        JobEntity job = runningJob();
        job.setStatus(JobStatus.FAILED);
        job.setErrorCode(JobErrorCode.TIMEOUT.name());
        job.setResult("Handler timed out after 30s");
        repository.save(job);

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertEquals(JobErrorCode.TIMEOUT.name(), after.getErrorCode(),
                "the code is what a program branches on; losing it turns every failure back into a message");
    }

    @Test
    @DisplayName("clearing the progress removes the stored value")
    void shouldRemoveTheProgressWhenItIsCleared() {
        runningJob();
        repository.updateProgress(jobId, 60.0, "step three");

        JobEntity job = repository.findById(jobId).orElseThrow();
        job.setProgress(null);
        job.setProgressMessage(null);
        repository.save(job);

        JobEntity after = repository.findById(jobId).orElseThrow();
        assertNull(after.getProgress(),
                "a Redis hash cannot hold a null, so clearing has to delete the field; leaving it "
                        + "behind makes a retried job report the previous attempt's progress");
        assertFalse(redis.opsForHash().hasKey("simplydone4j:job:" + jobId, "progress"));
    }
}
