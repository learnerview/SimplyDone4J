package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Terminal jobs must remain countable.
 *
 * <p>applyIndexWrites used to drop a job from every status index once it reached
 * SUCCESS/FAILED/DLQ/CANCELLED, so {@code countByStatus} was structurally pinned at
 * zero for all four terminal states. Nothing failed loudly: the dead-letter health
 * check could never report DOWN, the {@code simplydone4j.jobs.dead.letter} gauge always
 * read 0, and {@code MonitoringService.getCountByStatus()} reported zero finished jobs.
 * It was only visible by running the engine and reading the real gauge.
 *
 * <p>The index is now written for terminal states too and bounded with the same
 * retention TTL as the job record, so these tests pin both halves of that contract.
 */
@ExtendWith(MockitoExtension.class)
class TerminalStatusIndexTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private org.springframework.data.redis.core.ZSetOperations<String, String> zset;

    @Mock
    private org.springframework.data.redis.core.HashOperations<String, Object, Object> hash;

    private RedisJobRepository repository;

    @BeforeEach
    void setUp() {
        // Lenient because countByStatusReadsTheTerminalIndex builds its own template
        // and never touches the injected mock.
        lenient().when(redis.opsForZSet()).thenReturn(zset);
        lenient().when(redis.opsForHash()).thenReturn(hash);
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setKeyPrefix("test");
        repository = new RedisJobRepository(redis, JsonMapperHolder.MAPPER, props);
    }

    private static final class JsonMapperHolder {
        static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private JobEntity job(JobStatus status) {
        Instant now = Instant.now();
        return JobEntity.builder()
                .id("job-1")
                .jobType("greet")
                .producer("test")
                .status(status)
                .priority(JobPriority.NORMAL)
                .payload("{\"k\":\"v\"}")
                .nextRunAt(now)
                .updatedAt(now)
                .createdAt(now)
                .build();
    }

    @Test
    void deadLetteredJobIsCounted() {
        repository.save(job(JobStatus.DLQ));
        verify(zset).add(eq("test:idx:status:dlq"), eq("job-1"), anyDouble());
    }

    @Test
    void successfulJobIsCounted() {
        repository.save(job(JobStatus.SUCCESS));
        verify(zset).add(eq("test:idx:status:success"), eq("job-1"), anyDouble());
    }

    @Test
    void failedJobIsCounted() {
        repository.save(job(JobStatus.FAILED));
        verify(zset).add(eq("test:idx:status:failed"), eq("job-1"), anyDouble());
    }

    @Test
    void cancelledJobIsCounted() {
        repository.save(job(JobStatus.CANCELLED));
        verify(zset).add(eq("test:idx:status:cancelled"), eq("job-1"), anyDouble());
    }

    @Test
    void terminalIndexIsBoundedByTheSameTtlAsTheJobRecord() {
        repository.save(job(JobStatus.DLQ));
        // Without this the index would grow without bound, which is why terminal
        // members were originally dropped.
        verify(redis).expire(eq("test:idx:status:dlq"), any(java.time.Duration.class));
    }

    @Test
    void terminalJobLeavesNoMemberInNonTerminalIndexes() {
        repository.save(job(JobStatus.DLQ));
        verify(zset).remove("test:idx:status:queued", "job-1");
        verify(zset).remove("test:idx:status:running", "job-1");
        verify(zset).remove("test:idx:status:retry_scheduled", "job-1");
    }

    @Test
    void countByStatusReadsTheTerminalIndex() {
        var template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        var zsetOps = org.mockito.Mockito.mock(org.springframework.data.redis.core.ZSetOperations.class);
        org.mockito.Mockito.when(template.opsForZSet()).thenReturn(zsetOps);
        org.mockito.Mockito.when(zsetOps.zCard("test:idx:status:dlq")).thenReturn(7L);

        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setKeyPrefix("test");
        var repo = new RedisJobRepository(template, JsonMapperHolder.MAPPER, props);

        assertThat(repo.countByStatus(JobStatus.DLQ)).isEqualTo(7L);
    }
}
