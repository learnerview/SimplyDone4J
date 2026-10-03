package io.github.learnerview.simplydone4j.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneAutoConfiguration;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.FencingTokenSequence;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that a job taken off a priority queue is never silently lost.
 *
 * <p>Needs a real Redis: the entire defect lives in the interaction between the queue
 * ZSET and the job hashes, and a mocked {@code QueueRepository} returns whatever the test
 * tells it to -- it cannot model the fact that {@code claimReady} has already destroyed the
 * only remaining pointer to the un-dispatched jobs.
 */
class SchedulerEngineBatchRecoveryRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobRepository jobRepo;
    private RedisQueueRepository queueRepo;
    private SimplyDoneProperties props;
    private final List<String> executed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

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
                        + "that claimReady already removed the job from every queue.");
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
        executed.clear();
    }

    private void submit(String id, JobPriority priority) {
        JobEntity job = JobEntity.builder()
                .id(id)
                .jobType("test")
                .producer("p")
                .status(JobStatus.QUEUED)
                .priority(priority)
                .maxAttempts(3)
                .nextRunAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        jobRepo.save(job);
        queueRepo.enqueue(id, priority, Instant.now().toEpochMilli());
    }

    @Test
    @DisplayName("a failure part-way through a claimed batch must not strand the rest of it")
    void shouldNotStrandRemainderOfClaimedBatch() {
        int batchSize = props.getScheduler().getBatchSize();
        assertEquals(10, batchSize, "precondition: the batch is large enough to lose jobs");

        for (int i = 0; i < batchSize; i++) {
            submit("job-" + i, JobPriority.HIGH);
        }
        assertEquals(batchSize, queueRepo.queueSize(JobPriority.HIGH), "precondition: queue is loaded");

        // A fencing token that fails for the third call and then recovers. This is the
        // shape of a real transient Redis fault: INCR times out or the connection drops
        // for a few milliseconds mid-batch.
        AtomicInteger calls = new AtomicInteger();
        FencingTokenSequence flakyTokens = () -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("simulated Redis blip");
            }
            return String.format("%019d", calls.get());
        };

        SchedulerEngine scheduler = new SchedulerEngine(queueRepo, jobRepo,
                job -> executed.add(job.getId()), flakyTokens, props);

        scheduler.poll();

        // Everything the engine can still reach, counted two ways. The only pointer to a
        // claimed-but-undispatched job is the priority ZSET -- nothing in the engine ever
        // scans QUEUED status -- so a job missing from the ZSET is gone for good, while its
        // hash still reads "QUEUED" and getJob() reports it as healthy queued work.
        List<String> stillQueued = queueRepo.claimReady(JobPriority.HIGH, batchSize + 10);

        assertEquals(batchSize, executed.size() + stillQueued.size(),
                "Every job claimReady removed from the ZSET must have been dispatched or put "
                        + "back. " + executed.size() + " were executed and " + stillQueued.size()
                        + " remain queued, but " + batchSize + " were claimed; the rest were "
                        + "dropped from the queue with no recovery path.");
    }

    @Test
    @DisplayName("a saturated worker pool must not run handlers on the scheduler thread")
    void shouldNotRunHandlerInlineOnTheSchedulerThread() throws InterruptedException {
        // corePoolSize 1 / queueCapacity 1 saturates immediately, which is the state a real
        // pool reaches once handlers run longer than batchSize / pollingIntervalMs.
        props.getExecutor().setCorePoolSize(1);
        props.getExecutor().setMaxPoolSize(1);
        props.getExecutor().setQueueCapacity(1);

        // Built through the auto-configuration method rather than by hand, because the
        // rejection policy under test is chosen there and nowhere else.
        ThreadPoolTaskExecutor executor = new SimplyDoneAutoConfiguration().jobTaskExecutor(props);

        CountDownLatch poolBusy = new CountDownLatch(1);
        CountDownLatch releasePool = new CountDownLatch(1);
        Runnable blocker = () -> {
            poolBusy.countDown();
            try {
                releasePool.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            // Occupy the single worker thread AND the single queue slot, so the next submit
            // is rejected outright rather than merely parked.
            executor.submit(blocker);
            assertTrue(poolBusy.await(5, TimeUnit.SECONDS), "precondition: the worker pool is busy");
            executor.submit(blocker);

            submit("job-blocked", JobPriority.HIGH);

            AtomicReference<String> handlerThread = new AtomicReference<>();
            CountDownLatch handlerRan = new CountDownLatch(1);

            // Mirrors JobExecutorServiceImpl.execute: publish, then hand the task to the
            // pool. Which thread ends up running the handler is decided entirely by the
            // executor's rejection policy, so that is all this double needs to be.
            JobExecutorService executorService = job -> executor.submit(() -> {
                handlerThread.set(Thread.currentThread().getName());
                handlerRan.countDown();
            });

            // poll() is what @Scheduled calls, and it is the thread that submits.
            SchedulerEngine scheduler = new SchedulerEngine(queueRepo, jobRepo,
                    executorService, () -> "0000000000000000001", props);

            scheduler.poll();

            assertNull(handlerThread.get(),
                    "The handler ran on '" + handlerThread.get() + "'. poll() is the @Scheduled "
                            + "thread and shares its single thread with the lease reaper and the "
                            + "retry promoter, so running a handler inline there stalls all three "
                            + "for the handler's full duration.");
            assertFalse(handlerRan.await(500, TimeUnit.MILLISECONDS),
                    "the handler must not have run at all while the pool is saturated");
            assertEquals(1L, queueRepo.queueSize(JobPriority.HIGH),
                    "a rejected submit must leave the job on the queue, not lose it");
        } finally {
            releasePool.countDown();
            executor.shutdown();
        }
    }
}