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

import java.util.LinkedHashSet;
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
    @Mock RedisOperations<String, String> ops;

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
        /**
         * Runs the real SessionCallback body against a mocked RedisOperations so the
         * WATCH / range / MULTI / EXEC sequence is actually exercised rather than
         * being short-circuited by a stubbed return value.
         */
        private void runCallback(Set<ZSetOperations.TypedTuple<String>> candidates, List<Object> execResult) {
            lenient().when(ops.opsForZSet()).thenReturn(zSetOps);
            lenient().when(ops.exec()).thenReturn(execResult);
            when(zSetOps.rangeByScoreWithScores(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                    .thenReturn(candidates);

            when(redis.execute(any(SessionCallback.class))).thenAnswer(inv -> {
                SessionCallback<?> callback = inv.getArgument(0);
                return callback.execute(ops);
            });
        }

        private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
            @SuppressWarnings("unchecked")
            ZSetOperations.TypedTuple<String> t = mock(ZSetOperations.TypedTuple.class);
            when(t.getValue()).thenReturn(value);
            // The claim path only reads values, but keep the score realistic for any
            // future ordering assertion.
            lenient().when(t.getScore()).thenReturn(score);
            return t;
        }

        @Test
        void shouldClaimNextReadyJobViaTransaction() {
            runCallback(Set.of(tuple("job-1", 100L)), List.of(true));

            Optional<String> result = repo.claimNextReady(JobPriority.NORMAL);

            assertTrue(result.isPresent());
            assertEquals("job-1", result.get());
            verify(ops).watch("sd4j-test:queue:normal");
            verify(zSetOps).remove("sd4j-test:queue:normal", "job-1");
        }

        @Test
        void shouldReturnEmptyWhenNoJobsReady() {
            runCallback(Set.of(), List.of());

            Optional<String> result = repo.claimNextReady(JobPriority.LOW);

            assertFalse(result.isPresent());
        }

        @Test
        void shouldClaimUpToTheRequestedBatchSize() {
            runCallback(new LinkedHashSet<>(List.of(
                            tuple("job-1", 100L), tuple("job-2", 200L), tuple("job-3", 300L))),
                    List.of(true));

            List<String> claimed = repo.claimReady(JobPriority.HIGH, 2);

            // The zset range must be capped so a backlogged queue cannot be drained
            // into one unbounded claim.
            verify(zSetOps).rangeByScoreWithScores(eq("sd4j-test:queue:high"),
                    anyDouble(), anyDouble(), eq(0L), eq(2L));
            verify(zSetOps).remove("sd4j-test:queue:high", "job-1", "job-2", "job-3");
            assertEquals(List.of("job-1", "job-2", "job-3"), claimed);
        }

        @Test
        void shouldReturnNothingWhenTheWatchedKeyChangedUnderneath() {
            // exec() returning null is how Redis reports a lost WATCH.
            runCallback(Set.of(tuple("job-1", 100L)), null);

            assertEquals(List.of(), repo.claimReady(JobPriority.NORMAL, 5),
                    "A lost optimistic lock must not report jobs as claimed");
        }

        @Test
        void shouldReturnNothingForNonPositiveLimit() {
            assertEquals(List.of(), repo.claimReady(JobPriority.HIGH, 0));
            assertEquals(List.of(), repo.claimReady(JobPriority.HIGH, -1));
            verify(redis, never()).execute(any(SessionCallback.class));
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
