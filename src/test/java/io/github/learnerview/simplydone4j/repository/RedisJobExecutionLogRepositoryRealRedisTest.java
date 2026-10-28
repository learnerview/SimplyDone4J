package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
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

/**
 * Pins the order in which execution logs come back. The store pushes with {@code LPUSH},
 * so the list is newest-first in Redis but the API promises attempt order.
 */
class RedisJobExecutionLogRepositoryRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private RedisJobExecutionLogRepository repository;

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
                        + " (set -Dit.redis.host/-Dit.redis.port). A mock cannot show the list order.");
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
        props.getRetention().setStoreExecutionLogs(true);
        repository = new RedisJobExecutionLogRepository(redis, props);
    }

    @Test
    @DisplayName("execution logs come back oldest attempt first")
    void shouldReturnAttemptsInExecutionOrder() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            repository.save(JobExecutionLog.builder()
                    .jobId("job-1")
                    .attempt(attempt)
                    .status("FAILED")
                    .message("attempt " + attempt)
                    .durationMs(10L)
                    .executedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(attempt))
                    .build());
        }

        List<JobExecutionLog> logs = repository.findByJobIdOrderByAttemptAsc("job-1");

        assertEquals(List.of(1, 2, 3), logs.stream().map(JobExecutionLog::getAttempt).toList(),
                "LPUSH stores the newest first; the read has to reverse it");
    }
}
