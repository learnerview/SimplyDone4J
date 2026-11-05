package io.github.learnerview.simplydone4j.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Proves DLQ replay/discard against the real Redis compare-and-write and queue scripts.
 */
class DeadLetterRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimplyDoneProperties properties;
    private ObjectMapper mapper;
    private RedisJobRepository jobRepo;
    private RedisQueueRepository queueRepo;
    private DeadLetterServiceImpl service;

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
                        + " (set -Dit.redis.host/-Dit.redis.port).");
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
        properties = new SimplyDoneProperties();
        properties.setKeyPrefix("dlq-it");
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        jobRepo = new RedisJobRepository(redis, mapper, properties);
        queueRepo = new RedisQueueRepository(redis, properties);
        service = new DeadLetterServiceImpl(jobRepo, queueRepo, new JobMapper(mapper),
                mock(JobEventPublisher.class), new DefaultJobSignal(), properties);
    }

    @Test
    void replayMovesTheRecordToQueuedAndAdmitsIt() {
        JobEntity job = deadLetter("replay-me");
        jobRepo.save(job);

        assertEquals(1L, service.count());

        service.replay("replay-me");

        JobEntity stored = jobRepo.findById("replay-me").orElseThrow();
        assertEquals(JobStatus.QUEUED, stored.getStatus());
        assertEquals(0, stored.getAttemptCount());
        assertEquals(0L, jobRepo.countByStatus(JobStatus.DLQ));
        assertTrue(queueRepo.contains("replay-me", JobPriority.HIGH));
        assertEquals(1L, queueRepo.queueSize(JobPriority.HIGH));
    }

    @Test
    void replayRestoresDlqWhenCapacityIsFull() {
        properties.getQueue().setMaxDepth(1L);
        service = new DeadLetterServiceImpl(jobRepo, queueRepo,
                new JobMapper(mapper), mock(JobEventPublisher.class),
                new DefaultJobSignal(), properties);
        JobEntity filler = deadLetter("filler");
        filler.setStatus(JobStatus.SUCCESS);
        jobRepo.save(filler);
        assertTrue(queueRepo.enqueueIfUnderCapacity("filler", JobPriority.HIGH,
                Instant.now().toEpochMilli(), 1L));

        JobEntity job = deadLetter("blocked");
        jobRepo.save(job);

        assertThrows(QueueFullException.class, () -> service.replay("blocked"));
        assertEquals(JobStatus.DLQ, jobRepo.findById("blocked").orElseThrow().getStatus());
        assertEquals(1L, jobRepo.countByStatus(JobStatus.DLQ));
        assertFalse(queueRepo.contains("blocked", JobPriority.HIGH));
    }

    @Test
    void discardRemovesTheRecordAndItsIndexMembers() {
        JobEntity job = deadLetter("discard-me");
        jobRepo.save(job);

        service.discard("discard-me");

        assertTrue(jobRepo.findById("discard-me").isEmpty());
        assertEquals(0L, jobRepo.countByStatus(JobStatus.DLQ));
    }

    @Test
    void discardLosesCleanlyAfterAReplay() {
        JobEntity job = deadLetter("already-replayed");
        jobRepo.save(job);
        service.replay("already-replayed");

        assertThrows(IllegalArgumentException.class, () -> service.discard("already-replayed"));
        assertEquals(JobStatus.QUEUED, jobRepo.findById("already-replayed").orElseThrow().getStatus());
        assertTrue(queueRepo.contains("already-replayed", JobPriority.HIGH));
    }

    private static JobEntity deadLetter(String id) {
        Instant now = Instant.now();
        return JobEntity.builder()
                .id(id)
                .jobType("email")
                .producer("producer-1")
                .idempotencyKey(UUID.randomUUID().toString())
                .status(JobStatus.DLQ)
                .priority(JobPriority.HIGH)
                .attemptCount(3)
                .maxAttempts(3)
                .result("Max retries exceeded")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
