package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RedisJobExecutionLogRepository implements JobExecutionLogRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisJobExecutionLogRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String logKeyPrefix;
    private final boolean storeExecutionLogs;
    private final int maxExecutionLogsPerJob;
    private final int ttlHours;

    public RedisJobExecutionLogRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                                           SimplyDoneProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.logKeyPrefix = props.getKeyPrefix() + ":log:";
        this.storeExecutionLogs = props.getRetention().isStoreExecutionLogs();
        this.maxExecutionLogsPerJob = props.getRetention().getMaxExecutionLogsPerJob();
        this.ttlHours = (props.getTtlDays() * 24) + props.getTtlHours();
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
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, maxExecutionLogsPerJob - 1);
            redis.expire(key, java.time.Duration.ofHours(ttlHours));
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
        return logs;
    }

    private String logKey(String jobId) { return logKeyPrefix + jobId; }
}
