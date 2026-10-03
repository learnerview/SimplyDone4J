package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RedisQueueRepository implements QueueRepository {
        private final StringRedisTemplate redis;
    private final String queuePrefix;

    public RedisQueueRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        // Derive from keyPrefix unless explicitly overridden. Reading a separate
        // scheduler.queuePrefix default meant that setting `key-prefix: prod` moved
        // every data key to `prod:*` while the priority queues stayed behind at
        // `simplydone4j:queue:*`, silently defeating key-prefix tenant isolation.
        String configured = props.getScheduler().getQueuePrefix();
        this.queuePrefix = (configured != null && !configured.isBlank())
                ? configured
                : props.getKeyPrefix() + ":queue";
    }

    @Override
    public void enqueue(String jobId, JobPriority priority, long scheduledAtEpochMs) {
        redis.opsForZSet().add(queueKey(priority), jobId, scheduledAtEpochMs);
    }

    @Override
    public Optional<String> claimNextReady(JobPriority priority) {
        return claimReady(priority, 1).stream().findFirst();
    }

    @Override
    public List<String> claimReady(JobPriority priority, int limit) {
        if (limit <= 0) return List.of();
        String key = queueKey(priority);
        long now = System.currentTimeMillis();

        return redis.execute(new SessionCallback<List<String>>() {
            @Override
            @SuppressWarnings("unchecked")
            public List<String> execute(RedisOperations ops) throws DataAccessException {
                ops.watch(key);
                Set<ZSetOperations.TypedTuple<String>> candidates =
                        ((ZSetOperations<String, String>) ops.opsForZSet())
                                .rangeByScoreWithScores(key, 0, now, 0, limit);

                if (candidates == null || candidates.isEmpty()) {
                    ops.unwatch();
                    return List.of();
                }

                List<String> ids = new ArrayList<>(candidates.size());
                for (ZSetOperations.TypedTuple<String> candidate : candidates) {
                    ids.add(candidate.getValue());
                }

                ops.multi();
                ops.opsForZSet().remove(key, ids.toArray());
                List<Object> execResult = ops.exec();

                if (execResult == null || execResult.isEmpty()) {
                    return List.of();
                }
                return ids;
            }
        });
    }

    @Override
    public void remove(String jobId, JobPriority priority) {
        redis.opsForZSet().remove(queueKey(priority), jobId);
    }

    @Override
    public long queueSize(JobPriority priority) {
        Long size = redis.opsForZSet().zCard(queueKey(priority));
        return size != null ? size : 0L;
    }

    @Override
    public void clearQueue(JobPriority priority) {
        redis.delete(queueKey(priority));
    }

    @Override
    public void clearAll() {
        for (JobPriority p : JobPriority.values()) {
            clearQueue(p);
        }
    }

    private String queueKey(JobPriority priority) {
        return queuePrefix + ':' + priority.name().toLowerCase();
    }
}
