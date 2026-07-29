package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class RedisJobRepository implements JobRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisJobRepository.class);
    private static final String JOB_KEY_PREFIX = "simplydone4j:job:";
    private static final String STATUS_INDEX_PREFIX = "simplydone4j:idx:status:";
    private static final String IDEMPOTENCY_PREFIX = "simplydone4j:idempotency:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisJobRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(JobEntity job) {
        String key = jobKey(job.getId());
        Map<String, String> fields = objectMapper.convertValue(job, new TypeReference<>() {});
        fields.values().removeIf(v -> v == null);
        redis.opsForHash().putAll(key, fields);

        if (job.getStatus() != null) {
            double score;
            if (job.getStatus() == JobStatus.RUNNING && job.getVisibleAt() != null) {
                score = job.getVisibleAt().toEpochMilli();
            } else if (job.getNextRunAt() != null) {
                score = job.getNextRunAt().toEpochMilli();
            } else {
                return;
            }
            redis.opsForZSet().add(statusIndexKey(job.getStatus()), job.getId(), score);
        }

        if (job.getProducer() != null && job.getIdempotencyKey() != null) {
            redis.opsForValue().set(idempotencyKey(job.getProducer(), job.getIdempotencyKey()), job.getId());
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
    public List<JobEntity> findExpiredLeases(JobStatus status, Instant before, int limit) {
        return findReadyToRun(status, before, limit);
    }

    @Override
    public long countByStatus(JobStatus status) {
        Long size = redis.opsForZSet().zCard(statusIndexKey(status));
        return size != null ? size : 0L;
    }

    @Override
    public long countByStatusAndPriority(JobStatus status, JobPriority priority) {
        return countByStatus(status);
    }

    @Override
    public int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil,
                                  Instant now, JobStatus fromStatus, JobStatus toStatus) {
        Optional<JobEntity> opt = findById(jobId);
        if (opt.isEmpty()) return 0;
        JobEntity job = opt.get();
        if (job.getStatus() != fromStatus) return 0;

        redis.opsForZSet().remove(statusIndexKey(fromStatus), jobId);
        job.setStatus(toStatus);
        job.setLeaseToken(leaseToken);
        job.setLeaseOwner(workerId);
        job.setVisibleAt(visibleUntil);
        job.setStartedAt(now);
        job.setUpdatedAt(now);
        save(job);
        return 1;
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

    private static String jobKey(String jobId) { return JOB_KEY_PREFIX + jobId; }
    private static String statusIndexKey(JobStatus status) { return STATUS_INDEX_PREFIX + status.name().toLowerCase(); }
    private static String idempotencyKey(String producer, String idempotencyKey) { return IDEMPOTENCY_PREFIX + producer + ':' + idempotencyKey; }
}
