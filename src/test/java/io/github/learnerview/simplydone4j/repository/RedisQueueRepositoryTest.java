package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisQueueRepositoryTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;

    SimplyDoneProperties props;
    RedisQueueRepository repo;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        props.getScheduler().setQueuePrefix("sd4j-test:queue");
        lenient().when(redis.opsForZSet()).thenReturn(zSetOps);
        repo = new RedisQueueRepository(redis, props);
    }

    @Nested
    class Enqueue {
        @Test
        void shouldEnqueueJobToHighPriorityQueue() {
            repo.enqueue("job-1", JobPriority.HIGH, 1000L);
            verify(zSetOps).add("sd4j-test:queue:high", "job-1", 1000L);
        }

        @Test
        void shouldEnqueueJobToNormalPriorityQueue() {
            repo.enqueue("job-1", JobPriority.NORMAL, 1000L);
            verify(zSetOps).add("sd4j-test:queue:normal", "job-1", 1000L);
        }

        @Test
        void shouldEnqueueJobToLowPriorityQueue() {
            repo.enqueue("job-1", JobPriority.LOW, 1000L);
            verify(zSetOps).add("sd4j-test:queue:low", "job-1", 1000L);
        }

        @Test
        void shouldPreserveScoreOrdering() {
            repo.enqueue("job-early", JobPriority.HIGH, 100L);
            repo.enqueue("job-late", JobPriority.HIGH, 200L);

            verify(zSetOps).add("sd4j-test:queue:high", "job-early", 100L);
            verify(zSetOps).add("sd4j-test:queue:high", "job-late", 200L);
        }
    }

    @Nested
    class ClaimNextReady {
        @Test
        void shouldClaimNextReadyJobViaTransaction() {
            when(redis.execute(any(SessionCallback.class))).thenReturn(Optional.of("job-1"));

            Optional<String> result = repo.claimNextReady(JobPriority.NORMAL);
            assertTrue(result.isPresent());
            assertEquals("job-1", result.get());
        }

        @Test
        void shouldReturnEmptyWhenNoJobsReady() {
            when(redis.execute(any(SessionCallback.class))).thenReturn(Optional.empty());

            Optional<String> result = repo.claimNextReady(JobPriority.LOW);
            assertFalse(result.isPresent());
        }

        @Test
        void shouldHandleExceptionDuringClaim() {
            when(redis.execute(any(SessionCallback.class))).thenThrow(new DataAccessException("Redis tx failed") {});

            assertThrows(DataAccessException.class, () -> repo.claimNextReady(JobPriority.HIGH));
        }
    }

    @Nested
    class Remove {
        @Test
        void shouldRemoveJobFromQueue() {
            repo.remove("job-1", JobPriority.HIGH);
            verify(zSetOps).remove("sd4j-test:queue:high", "job-1");
        }

        @Test
        void shouldRemoveFromCorrectPriorityQueue() {
            repo.remove("job-1", JobPriority.LOW);
            verify(zSetOps).remove("sd4j-test:queue:low", "job-1");
            verify(zSetOps, never()).remove("sd4j-test:queue:high", "job-1");
            verify(zSetOps, never()).remove("sd4j-test:queue:normal", "job-1");
        }
    }

    @Nested
    class QueueSize {
        @Test
        void shouldReturnQueueSize() {
            when(zSetOps.zCard("sd4j-test:queue:high")).thenReturn(5L);
            assertEquals(5L, repo.queueSize(JobPriority.HIGH));
        }

        @Test
        void shouldReturnZeroWhenRedisReturnsNull() {
            when(zSetOps.zCard(anyString())).thenReturn(null);
            assertEquals(0L, repo.queueSize(JobPriority.HIGH));
        }
    }

    @Nested
    class ClearOperations {
        @Test
        void shouldClearSingleQueue() {
            repo.clearQueue(JobPriority.HIGH);
            verify(redis).delete("sd4j-test:queue:high");
        }

        @Test
        void shouldClearAllQueues() {
            repo.clearAll();
            verify(redis).delete("sd4j-test:queue:high");
            verify(redis).delete("sd4j-test:queue:normal");
            verify(redis).delete("sd4j-test:queue:low");
        }
    }
}
