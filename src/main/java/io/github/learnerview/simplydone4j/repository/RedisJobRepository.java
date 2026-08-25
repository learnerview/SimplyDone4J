package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class RedisJobRepository implements JobRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisJobRepository.class);
    private static final List<JobStatus> TERMINAL_STATUSES = List.of(
            JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.DLQ, JobStatus.CANCELLED);
    private static final List<JobStatus> NON_TERMINAL_STATUSES = List.of(
            JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.RETRY_SCHEDULED);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String jobKeyPrefix;
    private final String statusIndexPrefix;
    private final String statusPriorityIndexPrefix;
    private final String idempotencyPrefix;
    private final boolean clearPayloadOnCompletion;
    private final int ttlHours;

    public RedisJobRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                               SimplyDoneProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        String kp = props.getKeyPrefix();
        this.jobKeyPrefix = kp + ":job:";
        this.statusIndexPrefix = kp + ":idx:status:";
        this.statusPriorityIndexPrefix = kp + ":idx:status:priority:";
        this.idempotencyPrefix = kp + ":idempotency:";
        this.clearPayloadOnCompletion = props.getRetention().isClearPayloadOnCompletion();
        this.ttlHours = (props.getTtlDays() * 24) + props.getTtlHours();
    }

    @Override
    public void save(JobEntity job) {
        String key = jobKey(job.getId());
        Map<String, String> fields = objectMapper.convertValue(job, new TypeReference<>() {});
        fields.values().removeIf(v -> v == null);
        if (clearPayloadOnCompletion && TERMINAL_STATUSES.contains(job.getStatus())) {
            fields.remove("payload");
            redis.opsForHash().delete(key, "payload");
        }
        redis.opsForHash().putAll(key, fields);
        if (!TERMINAL_STATUSES.contains(job.getStatus())) {
            double score;
            if (job.getStatus() == JobStatus.RUNNING && job.getVisibleAt() != null) {
                score = job.getVisibleAt().toEpochMilli();
            } else if (job.getNextRunAt() != null) {
                score = job.getNextRunAt().toEpochMilli();
            } else {
                score = System.currentTimeMillis();
            }
            for (JobStatus s : NON_TERMINAL_STATUSES) {
                if (s != job.getStatus()) {
                    redis.opsForZSet().remove(statusIndexKey(s), job.getId());
                }
            }
            redis.opsForZSet().add(statusIndexKey(job.getStatus()), job.getId(), score);
            if (job.getPriority() != null) {
                for (JobStatus s : NON_TERMINAL_STATUSES) {
                    if (s != job.getStatus()) {
                        redis.opsForZSet().remove(statusPriorityIndexKey(s, job.getPriority()), job.getId());
                    }
                }
                redis.opsForZSet().add(statusPriorityIndexKey(job.getStatus(), job.getPriority()), job.getId(), score);
            }
        }
        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            // Purge from all non-terminal indexes so finished jobs don't linger
            // as zombie members in unbounded ZSETs (indexes carry no TTL).
            for (JobStatus s : NON_TERMINAL_STATUSES) {
                redis.opsForZSet().remove(statusIndexKey(s), job.getId());
                if (job.getPriority() != null) {
                    redis.opsForZSet().remove(statusPriorityIndexKey(s, job.getPriority()), job.getId());
                }
            }
            redis.expire(key, java.time.Duration.ofHours(ttlHours));
        }
    }

    @Override
    public Optional<JobEntity> findById(String jobId) {
        Map<Object, Object> entries = redis.opsForHash().entries(jobKey(jobId));
        if (entries.isEmpty()) return Optional.empty();
        Map<String, String> stringMap = new HashMap<>();
        entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
        return Optional.of(objectMapper.convertValue(stringMap, JobEntity.class));
    }

    @Override
    public Optional<JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey) {
        String jobId = redis.opsForValue().get(idempotencyKey(producer, idempotencyKey));
        if (jobId == null) return Optional.empty();
        return findById(jobId);
    }

    @Override
    public List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit) {
        Set<String> jobIds = redis.opsForZSet().rangeByScore(statusIndexKey(status), 0, before.toEpochMilli(), 0, limit);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream().map(this::findById).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
    }

    @Override
    public long countByStatus(JobStatus status) {
        Long size = redis.opsForZSet().zCard(statusIndexKey(status));
        return size != null ? size : 0L;
    }

    @Override
    public long countByStatusAndPriority(JobStatus status, JobPriority priority) {
        Long size = redis.opsForZSet().zCard(statusPriorityIndexKey(status, priority));
        return size != null ? size : 0L;
    }

    @Override
    @SuppressWarnings("unchecked")
    public int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil,
                                  Instant now, JobStatus fromStatus, JobStatus toStatus) {
        return redis.execute(new SessionCallback<Integer>() {
            @Override
            public Integer execute(RedisOperations ops) throws DataAccessException {
                String key = jobKey(jobId);
                ops.watch(key);

                Map<Object, Object> entries = ops.opsForHash().entries(key);
                if (entries.isEmpty()) {
                    ops.unwatch();
                    return 0;
                }

                Map<String, String> stringMap = new HashMap<>();
                entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
                JobEntity job = objectMapper.convertValue(stringMap, JobEntity.class);

                if (job.getStatus() != fromStatus) {
                    ops.unwatch();
                    return 0;
                }

                job.setStatus(toStatus);
                job.setLeaseToken(leaseToken);
                job.setLeaseOwner(workerId);
                job.setVisibleAt(visibleUntil);
                job.setStartedAt(now);
                job.setUpdatedAt(now);

                Map<String, String> fields = objectMapper.convertValue(job, new TypeReference<>() {});
                fields.values().removeIf(v -> v == null);

                ops.multi();
                for (JobStatus s : JobStatus.values()) {
                    ops.opsForZSet().remove(statusIndexKey(s), jobId);
                    for (JobPriority p : JobPriority.values()) {
                        ops.opsForZSet().remove(statusPriorityIndexKey(s, p), jobId);
                    }
                }
                if (clearPayloadOnCompletion && TERMINAL_STATUSES.contains(toStatus)) {
                    fields.remove("payload");
                    ops.opsForHash().delete(key, "payload");
                }
                ops.opsForHash().putAll(key, fields);
                if (TERMINAL_STATUSES.contains(toStatus)) {
                    ops.expire(key, java.time.Duration.ofHours(ttlHours));
                }
                double score;
                if (toStatus == JobStatus.RUNNING && visibleUntil != null) {
                    score = visibleUntil.toEpochMilli();
                } else if (job.getNextRunAt() != null) {
                    score = job.getNextRunAt().toEpochMilli();
                } else {
                    ops.unwatch();
                    return 0;
                }
                ops.opsForZSet().add(statusIndexKey(toStatus), jobId, score);
                if (job.getPriority() != null) {
                    ops.opsForZSet().add(statusPriorityIndexKey(toStatus, job.getPriority()), jobId, score);
                }

                List<Object> execResult = ops.exec();
                if (execResult == null || execResult.isEmpty()) return 0;
                return 1;
            }
        });
    }

    @Override
    public List<JobEntity> findByProducerAndStatus(String producer, JobStatus status) {
        return findByStatus(status).stream()
                .filter(j -> producer.equals(j.getProducer()))
                .collect(Collectors.toList());
    }

    @Override
    public List<JobEntity> findByStatus(JobStatus status) {
        Set<String> jobIds = redis.opsForZSet().range(statusIndexKey(status), 0, -1);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream().map(this::findById).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
    }

    private String jobKey(String jobId) { return jobKeyPrefix + jobId; }
    private String statusIndexKey(JobStatus status) { return statusIndexPrefix + status.name().toLowerCase(); }
    private String statusPriorityIndexKey(JobStatus status, JobPriority priority) { return statusPriorityIndexPrefix + status.name().toLowerCase() + ':' + priority.name().toLowerCase(); }
    private String idempotencyKey(String producer, String idempotencyKey) { return idempotencyPrefix + producer + ':' + idempotencyKey; }
}
