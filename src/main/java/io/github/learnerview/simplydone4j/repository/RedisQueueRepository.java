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
import java.util.OptionalLong;

public final class RedisQueueRepository implements QueueRepository {

    /**
     * Capacity-checked enqueue in one atomic round trip.
     *
     * <p>The capacity is global across the three priority queues. Redis executes the
     * size check and the write without interleaving, so concurrent submissions cannot
     * overshoot the configured bound.</p>
     *
     * <p>All queue keys must share a Redis hash slot when Redis Cluster is enabled.
     * Use a key prefix containing a hash tag, such as {@code simplydone4j:{jobs}}, or
     * use Redis Standalone/Sentinel.</p>
     */
    private static final RedisScript<Long> ENQUEUE_IF_UNDER_CAPACITY_SCRIPT = new DefaultRedisScript<>("""
            local target = KEYS[tonumber(ARGV[3])]
            local maxDepth = tonumber(ARGV[1])
            local depth = redis.call('ZCARD', KEYS[1])
                    + redis.call('ZCARD', KEYS[2])
                    + redis.call('ZCARD', KEYS[3])
            if depth >= maxDepth then
                return 0
            end
            redis.call('ZADD', target, tonumber(ARGV[4]), ARGV[2])
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
        Long result = redis.execute(ENQUEUE_IF_UNDER_CAPACITY_SCRIPT, queueKeys(),
                String.valueOf(maxDepth), jobId, queueIndex(priority),
                String.valueOf(scheduledAtEpochMs));
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

        for (int attempt = 0; attempt < 3; attempt++) {
            // null means EXEC was aborted by a concurrent writer. Returning an empty list
            // here used to look identical to "nothing is due", which made the scheduler
            // sleep through a busy queue. Retry the compare-and-remove instead.
            List<String> claimed = redis.execute(new SessionCallback<List<String>>() {
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
                        return null;
                    }
                    return ids;
                }
            });
            if (claimed != null) {
                return claimed;
            }
        }
        return List.of();
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
    public boolean contains(String jobId, JobPriority priority) {
        return redis.opsForZSet().score(queueKey(priority), jobId) != null;
    }

    @Override
    public OptionalLong oldestScheduledAt(JobPriority priority) {
        Set<ZSetOperations.TypedTuple<String>> entries =
                redis.opsForZSet().rangeWithScores(queueKey(priority), 0, 0);
        if (entries == null || entries.isEmpty()) {
            return OptionalLong.empty();
        }
        Double score = entries.iterator().next().getScore();
        return score == null ? OptionalLong.empty() : OptionalLong.of(score.longValue());
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

    private List<String> queueKeys() {
        return List.of(
                queueKey(JobPriority.HIGH),
                queueKey(JobPriority.NORMAL),
                queueKey(JobPriority.LOW));
    }

    private String queueIndex(JobPriority priority) {
        return switch (priority) {
            case HIGH -> "1";
            case NORMAL -> "2";
            case LOW -> "3";
        };
    }
}
