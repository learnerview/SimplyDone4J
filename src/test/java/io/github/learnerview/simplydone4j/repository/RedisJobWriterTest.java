package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisJobWriterTest {

    @Test
    void saveFailsClosedWhenTheScriptReturnsNoResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        RedisJobWriter writer = writer(redis);

        assertThrows(IllegalStateException.class, () -> writer.save(job()));

        verify(redis, never()).opsForHash();
        verify(redis, never()).opsForZSet();
    }

    @Test
    void fencedSaveFailsClosedWhenTheScriptReturnsNoResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        RedisJobWriter writer = writer(redis);

        assertFalse(writer.saveIfLeaseHeld(job(), "lease-token"));

        verify(redis, never()).opsForHash();
        verify(redis, never()).opsForZSet();
    }

    private RedisJobWriter writer(StringRedisTemplate redis) {
        RedisJobMapping mapping = new RedisJobMapping(PersistenceJson.mapper(), "sd4j:test", false, 24);
        return new RedisJobWriter(redis, mapping);
    }

    private JobEntity job() {
        return JobEntity.builder()
                .id("job-1")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.HIGH)
                .build();
    }
}
