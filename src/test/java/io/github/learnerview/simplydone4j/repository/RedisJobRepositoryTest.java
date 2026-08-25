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
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisJobRepositoryTest {

    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashOps;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock ValueOperations<String, String> valueOps;

    ObjectMapper objectMapper;
    RedisJobRepository repo;
    Map<String, Set<String>> zSetData;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

        zSetData = new HashMap<>();

        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);

        lenient().doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            zSetData.computeIfAbsent(key, k -> new HashSet<>()).add(value);
            return true;
        }).when(zSetOps).add(anyString(), anyString(), anyDouble());

        lenient().doAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            Set<String> set = zSetData.get(key);
            if (set != null) {
                set.remove(value);
            }
            return 1L;
        }).when(zSetOps).remove(anyString(), anyString());

        lenient().when(zSetOps.zCard(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Set<String> set = zSetData.get(key);
            return set != null ? (long) set.size() : 0L;
        });

        SimplyDoneProperties props = new SimplyDoneProperties();
        props.setKeyPrefix("sd4j-test");
        props.setTtlDays(1);
        repo = new RedisJobRepository(redis, objectMapper, props);
    }

    @Test
    void shouldReturnZeroForEmptyCompoundIndex() {
        assertEquals(0, repo.countByStatusAndPriority(JobStatus.RUNNING, JobPriority.HIGH));
    }

    @Test
    void shouldPurgeTerminalJobFromAllStatusIndexes() {
        JobEntity running = JobEntity.builder()
                .id("leak-1")
                .jobType("test")
                .producer("app")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .visibleAt(Instant.now().plusSeconds(30))
                .build();
        repo.save(running);
        assertEquals(1, repo.countByStatus(JobStatus.RUNNING));
        assertEquals(1, repo.countByStatusAndPriority(JobStatus.RUNNING, JobPriority.NORMAL));

        JobEntity finished = JobEntity.builder()
                .id("leak-1")
                .jobType("test")
                .producer("app")
                .status(JobStatus.SUCCESS)
                .priority(JobPriority.NORMAL)
                .completedAt(Instant.now())
                .build();
        repo.save(finished);

        assertEquals(0, repo.countByStatus(JobStatus.RUNNING));
        assertEquals(0, repo.countByStatusAndPriority(JobStatus.RUNNING, JobPriority.NORMAL));
        verify(redis).expire(startsWith("sd4j-test:job:leak-1"), any(java.time.Duration.class));
    }
}
