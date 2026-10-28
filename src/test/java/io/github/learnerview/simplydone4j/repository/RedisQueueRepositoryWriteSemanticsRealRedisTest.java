package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the atomicity of {@link RedisQueueRepository#enqueueIfUnderCapacity} against a real
 * Redis.
 *
 * <p>The defect was a {@code ZCARD} followed by a separate {@code ZADD}: two submits could
 * both observe a free slot and both be admitted, so the queue overshot {@code maxDepth}.
 * A mock cannot show the interleaving; only the server executing the check and the write
 * as one script can settle it, which is exactly what the concurrent case below requires.
 */
class RedisQueueRepositoryWriteSemanticsRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisQueueRepository repository;

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
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot prove that a "
                        + "size check and a write execute as one atomic script.");
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
        repository = new RedisQueueRepository(redis, new SimplyDoneProperties());
    }

    @Test
    @DisplayName("enqueueIfUnderCapacity admits below the cap and refuses at it")
    void shouldEnforceCapacity() {
        assertTrue(repository.enqueueIfUnderCapacity("job-1", JobPriority.NORMAL, 1L, 2L));
        assertTrue(repository.enqueueIfUnderCapacity("job-2", JobPriority.NORMAL, 2L, 2L));
        assertFalse(repository.enqueueIfUnderCapacity("job-3", JobPriority.NORMAL, 3L, 2L),
                "the third entry must be refused once the cap of two is reached");
        assertEquals(2L, repository.queueSize(JobPriority.NORMAL));
    }

    @Test
    @DisplayName("the cap counts every priority queue together")
    void shouldCountAllPriorityQueuesTowardsTheCap() {
        assertTrue(repository.enqueueIfUnderCapacity("job-1", JobPriority.HIGH, 1L, 2L));
        assertTrue(repository.enqueueIfUnderCapacity("job-2", JobPriority.NORMAL, 2L, 2L));
        assertFalse(repository.enqueueIfUnderCapacity("job-3", JobPriority.LOW, 3L, 2L),
                "the cap is a total across the priority queues, so a LOW submission is "
                        + "refused once HIGH and NORMAL together hold two jobs");
        assertEquals(1L, repository.queueSize(JobPriority.HIGH));
        assertEquals(1L, repository.queueSize(JobPriority.NORMAL));
        assertEquals(0L, repository.queueSize(JobPriority.LOW));
    }

    @Test
    @DisplayName("concurrent submits must never exceed the cap")
    void shouldNotOvershootUnderConcurrency() throws Exception {
        long cap = 25;
        int attempts = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger admitted = new AtomicInteger();
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String id = "job-" + i;
                tasks.add(() -> {
                    if (repository.enqueueIfUnderCapacity(id, JobPriority.HIGH, 1L, cap)) {
                        admitted.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(cap, admitted.get(),
                "exactly the cap worth of submissions may be admitted");
        assertEquals(cap, repository.queueSize(JobPriority.HIGH),
                "the check-then-enqueue race could overshoot the cap; the atomic script cannot");
    }

    @Test
    @DisplayName("the pipelined depth read agrees with the per-queue reads")
    void shouldReadEveryDepthInOneGo() {
        repository.enqueue("job-1", JobPriority.HIGH, 1L);
        repository.enqueue("job-2", JobPriority.HIGH, 2L);
        repository.enqueue("job-3", JobPriority.NORMAL, 3L);

        Map<JobPriority, Long> depths = repository.queueDepths();

        assertEquals(2L, depths.get(JobPriority.HIGH));
        assertEquals(1L, depths.get(JobPriority.NORMAL));
        assertEquals(0L, depths.get(JobPriority.LOW));
        assertEquals(3, depths.size(), "every priority must be present, including the empty ones");

        for (JobPriority priority : JobPriority.values()) {
            assertEquals(repository.queueSize(priority), depths.get(priority),
                    "the pipelined read must agree with the single-queue read for " + priority);
        }
    }
}
