package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two properties that make the idempotency namespace work: exactly one place
 * owns the key, and a failed enqueue gives the key back exactly once.
 *
 * <p>The first property is structural. The submission path writes the key and the read
 * path looks it up; if those two ever disagree about the key's name, a duplicate
 * submission is accepted and the work runs twice. That is not visible in a unit test with
 * a mocked store, so this test drives the real store, the real job repository and the real
 * queue against a real Redis and then counts the keys in the database.</p>
 */
class IdempotencyOwnershipRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimplyDoneProperties props;
    private RedisIdempotencyStore store;
    private RedisJobRepository jobRepository;
    private RedisQueueRepository queueRepository;
    private JobSubmissionServiceImpl submissionService;

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
                        + "which keys the submission path actually writes.");
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
        store = new RedisIdempotencyStore(redis, props);
        jobRepository = new RedisJobRepository(redis, props);
        queueRepository = new RedisQueueRepository(redis, props);

        submissionService = new JobSubmissionServiceImpl(
                jobRepository,
                queueRepository,
                request -> io.github.learnerview.simplydone4j.api.spi.AdmissionDecision.allow(),
                props,
                new JobMapper(tools.jackson.databind.json.JsonMapper.builder().build()),
                new JobEventPublisher((ApplicationEventPublisher) event -> { }),
                store,
                Validation.buildDefaultValidatorFactory().getValidator(),
                io.github.learnerview.simplydone4j.metrics.JobMetrics.NOOP);
    }

    private JobSubmissionRequest request(String key) {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey(key);
        return req;
    }

    private List<String> keysMatching(String pattern) {
        List<String> keys = new ArrayList<>();
        redis.keys(pattern).forEach(keys::add);
        return keys;
    }

    @Test
    @DisplayName("the submission path writes the idempotency key exactly once, in the store's namespace")
    void shouldWriteTheKeyExactlyOnceInTheStoresNamespace() {
        var response = submissionService.submit("producer-1", request("key-1"));

        List<String> idempotencyKeys = keysMatching("*idempotency*");
        assertEquals(1, idempotencyKeys.size(),
                "the idempotency namespace must have exactly one owner, but the submission "
                        + "path wrote " + idempotencyKeys);
        assertEquals("simplydone4j:idempotency:producer-1:key-1", idempotencyKeys.get(0));

        assertEquals(response.getJobId(), store.lookup("producer-1", "key-1").orElse(null),
                "the key the store owns must point at the job that was just submitted");
    }

    @Test
    @DisplayName("a repeated submission returns the same job and does not create a second one")
    void shouldReturnTheSameJobForARepeatedSubmission() {
        var first = submissionService.submit("producer-1", request("key-2"));
        var second = submissionService.submit("producer-1", request("key-2"));

        assertEquals(first.getJobId(), second.getJobId());
        assertEquals(1, keysMatching("*idempotency*").size(),
                "a duplicate submission must not claim a second key");
        assertTrue(jobRepository.findById(first.getJobId()).isPresent());
    }

    @Test
    @DisplayName("a failed enqueue releases the key exactly once, so the key can be resubmitted")
    void shouldReleaseTheKeyExactlyOnceAfterAFailedEnqueue() {
        // A queue that is already at capacity rejects the enqueue, which is the
        // compensation path's trigger.
        props.getQueue().setMaxDepth(0);

        assertThrows(QueueFullException.class, () -> submissionService.submit("producer-1", request("key-3")));

        assertTrue(store.lookup("producer-1", "key-3").isEmpty(),
                "the key must be released, otherwise this producer can never submit it again");
        assertEquals(0, keysMatching("*idempotency*").size(),
                "the released key must be gone from Redis, not merely invisible to the store");

        // And the key is usable again, which is the whole point of releasing it.
        props.getQueue().setMaxDepth(10);
        var resubmitted = submissionService.submit("producer-1", request("key-3"));
        assertNotNull(resubmitted.getJobId());
        assertEquals(resubmitted.getJobId(), store.lookup("producer-1", "key-3").orElse(null));
    }

    @Test
    @DisplayName("a key is scoped to its producer")
    void shouldScopeTheKeyToItsProducer() {
        var first = submissionService.submit("producer-1", request("key-4"));
        var second = submissionService.submit("producer-2", request("key-4"));

        assertNotEquals(first.getJobId(), second.getJobId(),
                "two producers using the same key must not collide");
        assertEquals(2, keysMatching("*idempotency*").size());
    }

    @Test
    @DisplayName("the store refuses to release a key that a successor has taken")
    void shouldNotReleaseAKeyOwnedBySomebodyElse() {
        store.claim("producer-1", "key-5", "job-a");
        store.commit("producer-1", "key-5", "job-a");

        assertFalse(store.releaseIfOwnedBy("producer-1", "key-5", "job-b"));
        assertEquals("job-a", store.lookup("producer-1", "key-5").orElse(null));

        assertTrue(store.releaseIfOwnedBy("producer-1", "key-5", "job-a"));
        assertTrue(store.lookup("producer-1", "key-5").isEmpty());
    }
}
