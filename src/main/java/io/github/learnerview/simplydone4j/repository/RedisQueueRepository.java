package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class RedisQueueRepository implements QueueRepository {

    /**
     * Capacity-checked enqueue in one atomic round trip.
     *
     * <p>A plain {@code ZCARD} followed by {@code ZADD} is two commands: two submits can
     * both read {@code maxDepth - 1} and both write, overshooting the cap. Running the
     * test and the write inside one script means Redis executes them with no interleaving.
     *
     * <p>{@code ARGV} order is max depth, job id, score. The target queue is the only key,
     * so the script is safe under Redis Cluster where keys must share a hash slot.
     */
    private static final RedisScript<Long> ENQUEUE_IF_UNDER_CAPACITY_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local maxDepth = tonumber(ARGV[1])
            if redis.call('ZCARD', key) >= maxDepth then
                return 0
            end
            redis.call('ZADD', key, tonumber(ARGV[3]), ARGV[2])
            return 1
            """, Long.class);

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
    public boolean enqueueIfUnderCapacity(String jobId, JobPriority priority, long scheduledAtEpochMs,
                                          long maxDepth) {
        Long result = redis.execute(ENQUEUE_IF_UNDER_CAPACITY_SCRIPT, List.of(queueKey(priority)),
                String.valueOf(maxDepth), jobId, String.valueOf(scheduledAtEpochMs));
        return result != null && result == 1L;
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
            public <K, V> List<String> execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                ops.watch(key);
                Set<ZSetOperations.TypedTuple<String>> candidates =
                        ops.opsForZSet().rangeByScoreWithScores(key, 0, now, 0, limit);

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
