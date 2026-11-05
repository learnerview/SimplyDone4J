package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class RedisJobExecutionLogRepository implements JobExecutionLogRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisJobExecutionLogRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String logKeyPrefix;
    private final boolean storeExecutionLogs;
    private final int maxExecutionLogsPerJob;
    /**
     * Execution logs have their own retention window. They are a debugging trail rather than
     * job retention state, so the job's {@code ttl-days}/{@code ttl-hours} window must not
     * shorten them.
     */
    private final long logTtlSeconds;

    /**
     * Push, trim and expire in one script. Three separate commands leave a window where a
     * crash after LPUSH but before EXPIRE creates an immortal log key.
     */
    private static final RedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]))
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    public RedisJobExecutionLogRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                                           SimplyDoneProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.logKeyPrefix = props.getKeyPrefix() + ":log:";
        this.storeExecutionLogs = props.getRetention().isStoreExecutionLogs();
        // A zero/negative limit must not turn LTRIM(0, -1) into "keep every entry".
        this.maxExecutionLogsPerJob = Math.max(1, props.getRetention().getMaxExecutionLogsPerJob());
        this.logTtlSeconds = java.time.Duration.ofDays(
                Math.max(1, props.getRetention().getExecutionLogTtlDays())).toSeconds();
    }

    @Override
    public void save(JobExecutionLog executionLog) {
        if (!storeExecutionLogs) return;
        if (executionLog.getId() == null) {
            executionLog.setId(UUID.randomUUID().toString());
        }
        try {
            String json = objectMapper.writeValueAsString(executionLog);
            String key = logKey(executionLog.getJobId());
            redis.execute(SAVE_SCRIPT, List.of(key), json,
                    String.valueOf(maxExecutionLogsPerJob - 1),
                    String.valueOf(logTtlSeconds));
        } catch (Exception e) {
            log.warn("Failed to save execution log for job {}: {}", executionLog.getJobId(), e.getMessage());
        }
    }

    @Override
    public List<JobExecutionLog> findByJobIdOrderByAttemptAsc(String jobId) {
        List<String> entries = redis.opsForList().range(logKey(jobId), 0, -1);
        if (entries == null || entries.isEmpty()) return List.of();

        List<JobExecutionLog> logs = new ArrayList<>(entries.size());
        for (String entry : entries) {
            try {
                logs.add(objectMapper.readValue(entry, JobExecutionLog.class));
            } catch (Exception e) {
                log.warn("Failed to deserialize log entry: {}", e.getMessage());
            }
        }
        logs.sort(Comparator.comparingInt(JobExecutionLog::getAttempt)
                .thenComparing(JobExecutionLog::getExecutedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return logs;
    }

    private String logKey(String jobId) { return logKeyPrefix + jobId; }
}
