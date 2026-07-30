package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RedisQueueRepository implements QueueRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisQueueRepository.class);
    private final StringRedisTemplate redis;
    private final String queuePrefix;

    public RedisQueueRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        this.queuePrefix = props.getScheduler().getQueuePrefix();
    }

    @Override
    public void enqueue(String jobId, JobPriority priority, long scheduledAtEpochMs) {
        redis.opsForZSet().add(queueKey(priority), jobId, scheduledAtEpochMs);
    }

    @Override
    public Optional<String> claimNextReady(JobPriority priority) {
        String key = queueKey(priority);
        long now = System.currentTimeMillis();

        return redis.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Optional<String> execute(RedisOperations ops) throws DataAccessException {
                ops.watch(key);
                Set<ZSetOperations.TypedTuple<String>> results =
                        ops.opsForZSet().rangeByScoreWithScores(key, 0, now, 0, 1);

                if (results == null || results.isEmpty()) {
                    ops.unwatch();
                    return Optional.empty();
                }

                String jobId = results.iterator().next().getValue();
                ops.multi();
                ops.opsForZSet().remove(key, jobId);
                List<Object> execResult = ops.exec();

                if (execResult == null || execResult.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(jobId);
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
