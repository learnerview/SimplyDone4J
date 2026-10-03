package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class RedisJobRepository implements JobRepository {
    private static final Logger log = LoggerFactory.getLogger(RedisJobRepository.class);
    private static final List<JobStatus> TERMINAL_STATUSES = List.of(
            JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.DLQ, JobStatus.CANCELLED);
    
    private static final int FENCED_WRITE_ATTEMPTS = 3;
    private static final int LEASE_LOST = 0;
    private static final int WRITTEN = 1;
    private static final int TRANSACTION_ABORTED = 2;

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
        // One retention window, floored at an hour, shared by the job hash, the terminal
        // status index and (in RedisJobExecutionLogRepository) the execution log.
        //
        // The floor has to live here rather than at each use site. A raw zero reaches Redis
        // as `EXPIRE key 0`, which DELETES the key instead of expiring it -- so a
        // `ttl-days: 0` + `ttl-hours: 0` configuration wiped the whole `idx:status:dlq`
        // ZSET on every terminal write (countByStatus read 0, the dead-letter gauge never
        // moved, requeue could not find the job) and wiped every execution log immediately
        // after writing it, while applyRetention's own Math.max kept the job hash alive.
        // Clamping once makes it impossible for the three to disagree.
        this.ttlHours = Math.max(1, (props.getTtlDays() * 24) + props.getTtlHours());
    }

    @Override
    public void save(JobEntity job) {
        String key = jobKey(job.getId());
        writeFields(redis, key, job, fieldsFor(job));
        applyIndexWrites(redis, job);
        applyRetention(redis, key, job);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses a bounded retry because {@code EXEC} returning {@code null} means "someone
     * else mutated this key between WATCH and EXEC" -- an aborted optimistic transaction,
     * not a lost lease. Treating that as a lost lease would strand a job that in fact still
     * holds a valid one. Only a genuine token mismatch reports lease loss.</p>
     */
    @Override
    public boolean saveIfLeaseHeld(JobEntity job, String expectedLeaseToken) {
        if (expectedLeaseToken == null) return false;

        for (int attempt = 0; attempt < FENCED_WRITE_ATTEMPTS; attempt++) {
            @SuppressWarnings("unchecked")
            Integer outcome = redis.execute(new SessionCallback<Integer>() {
                // SessionCallback declares execute(RedisOperations<K,V>), so the generic
                // signature is fixed by the framework. Every command issued here targets
                // this job's String->String hash, so narrow once and then work typed.
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // See saveIfLeaseHeld: narrow the framework's generic signature once.
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                    String key = jobKey(job.getId());
                    ops.watch(key);

                    Object persistedToken = ops.opsForHash().get(key, "leaseToken");
                    if (persistedToken == null || !expectedLeaseToken.equals(persistedToken.toString())) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    Map<String, String> fields = fieldsFor(job);
                    ops.multi();
                    writeFields(ops, key, job, fields);
                    applyIndexWrites(ops, job);
                    applyRetention(ops, key, job);

                    List<Object> execResult = ops.exec();
                    return execResult != null && !execResult.isEmpty() ? WRITTEN : TRANSACTION_ABORTED;
                }
            });

            if (outcome == null) {
                return false;
            }
            if (outcome == LEASE_LOST) return false;
            if (outcome == WRITTEN) return true;
            // TRANSACTION_ABORTED -- a concurrent writer touched the key; retry the compare-and-write.
        }

        log.warn("Gave up writing job {} after {} attempts due to concurrent modification",
                job.getId(), FENCED_WRITE_ATTEMPTS);
        return false;
    }

    private Map<String, String> fieldsFor(JobEntity job) {
        Map<String, String> fields = objectMapper.convertValue(job, new TypeReference<>() {});
        if (clearPayloadOnCompletion && TERMINAL_STATUSES.contains(job.getStatus())) {
            fields.remove("payload");
        }
        return fields;
    }

    /**
     * Hash fields that must be removed rather than written.
     *
     * <p>A Redis hash write cannot express "set this to null": {@code HSET} stores a
     * string, so dropping a null from the field map simply leaves whatever the previous
     * write stored. Every caller that clears state -- {@code RetryServiceImpl} nulling
     * the lease and {@code nextRunAt}, {@code DeadLetterServiceImpl} nulling the terminal
     * fields -- therefore appeared to succeed while the stale values survived, and a
     * requeued job still reported {@code result = "Max retries exceeded"} alongside a
     * stale {@code leaseToken} that a zombie worker from the dead run could still match.
     * Collecting the null-valued field names and issuing {@code HDEL} for them is what
     * makes clearing actually clear.
     */
    private List<String> nullFieldNames(JobEntity job) {
        Map<String, String> all = objectMapper.convertValue(job, new TypeReference<>() {});
        List<String> names = new ArrayList<>();
        all.forEach((name, value) -> {
            if (value == null) {
                names.add(name);
            }
        });
        if (clearPayloadOnCompletion && TERMINAL_STATUSES.contains(job.getStatus())) {
            names.add("payload");
        }
        return names;
    }

    /** Applies a job's field map, deleting any field whose value is now null. */
    private void writeFields(RedisOperations<String, String> ops, String key, JobEntity job, Map<String, String> fields) {
        List<String> toDelete = nullFieldNames(job);
        if (!toDelete.isEmpty()) {
            ops.opsForHash().delete(key, toDelete.toArray());
        }
        fields.values().removeIf(Objects::isNull);
        ops.opsForHash().putAll(key, fields);
    }

    /**
     * Keeps or drops the retention TTL.
     *
     * <p>Terminal jobs get the configured TTL; anything else must have any TTL
     * <em>removed</em>. A job that exhausts its retries is written as {@code DLQ} and
     * given an expiry, so the record is already scheduled for deletion. Requeueing it
     * resets the status to {@code QUEUED}, but a plain write leaves that expiry in
     * place -- Redis then deletes a job that is queued and waiting to run, leaving a
     * zombie member on the priority ZSET pointing at a job that no longer exists.
     */
    private void applyRetention(RedisOperations<String, String> ops, String key, JobEntity job) {
        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            ops.expire(key, Duration.ofHours(Math.max(1, ttlHours)));
        } else {
            ops.persist(key);
        }
    }

    /**
     * Keeps the status indexes consistent with the job's current state. Runs either against
     * the template directly or as queued commands inside a {@code MULTI} block.
     */
    private void applyIndexWrites(RedisOperations<String, String> ops, JobEntity job) {
        String jobId = job.getId();

        // Purge from every index the job is not currently a member of, so a job never
        // lingers as a zombie in an index it has left (indexes carry no TTL).
        //
        // This iterates ALL statuses, not just the non-terminal ones. Restricting it to
        // NON_TERMINAL_STATUSES meant a job requeued out of DLQ was added to
        // idx:status:queued while remaining a member of idx:status:dlq, so
        // countByStatus(DLQ) never decreased: health stayed DOWN forever, the
        // dead-letter gauge never cleared, and requeueAll(limit) kept selecting
        // already-requeued jobs and burning its budget on requeue() calls that threw.
        for (JobStatus s : JobStatus.values()) {
            if (s != job.getStatus()) {
                ops.opsForZSet().remove(statusIndexKey(s), jobId);
                if (job.getPriority() != null) {
                    ops.opsForZSet().remove(statusPriorityIndexKey(s, job.getPriority()), jobId);
                }
            }
        }

        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            // Terminal jobs are indexed too. They used to be dropped from every index
            // instead, which left countByStatus(SUCCESS|FAILED|DLQ|CANCELLED) permanently
            // at zero: the dead-letter health check could never fire, the
            // simplydone4j.jobs.dead.letter gauge always read 0, and
            // MonitoringService.getCountByStatus() reported 0 for every finished job.
            // Bounded by giving the index key the same retention TTL as the job record,
            // so members disappear exactly when the jobs they point at do.
            ops.opsForZSet().add(statusIndexKey(job.getStatus()), jobId, terminalScore(job));
            ops.expire(statusIndexKey(job.getStatus()), Duration.ofHours(ttlHours));
            return;
        }

        double score = indexScore(job);
        ops.opsForZSet().add(statusIndexKey(job.getStatus()), jobId, score);
        if (job.getPriority() != null) {
            ops.opsForZSet().add(statusPriorityIndexKey(job.getStatus(), job.getPriority()), jobId, score);
        }
    }

    /**
     * Ordering value for a finished job. Terminal members are only ever counted or
     * listed, never selected by a time window, so the completion time is enough to
     * keep the ZSET sensibly ordered for debugging.
     */
    private double terminalScore(JobEntity job) {
        if (job.getUpdatedAt() != null) {
            return job.getUpdatedAt().toEpochMilli();
        }
        if (job.getNextRunAt() != null) {
            return job.getNextRunAt().toEpochMilli();
        }
        return 0L;
    }

    private double indexScore(JobEntity job) {
        if (job.getStatus() == JobStatus.RUNNING && job.getVisibleAt() != null) {
            return job.getVisibleAt().toEpochMilli();
        }
        if (job.getNextRunAt() != null) {
            return job.getNextRunAt().toEpochMilli();
        }
        return System.currentTimeMillis();
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
            // See saveIfLeaseHeld: SessionCallback fixes the generic signature, so narrow
            // once here. Every command below targets this job's String->String hash.
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // SessionCallback fixes this signature as generic. Every command here
                // addresses this job's String->String hash, so narrow once via cast and
                // then work in typed operations throughout.
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                String key = jobKey(jobId);
                ops.watch(key);

                // RedisOperations.opsForHash() keeps its own type variables, so hash
                // entries come back as Map<Object,Object> regardless of the key/value
                // types; copy them into a String map before deserialising.
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

                Map<String, String> fields = fieldsFor(job);

                // Resolve the score BEFORE opening the transaction. Redis rejects
                // UNWATCH inside MULTI, so bailing out after ops.multi() would abort
                // the whole MULTI and leave the connection in a broken state -- and
                // the job has already been ZREM'd from its queue by claimReady, so it
                // would be lost with no outcome recorded.
                double score;
                if (toStatus == JobStatus.RUNNING && visibleUntil != null) {
                    score = visibleUntil.toEpochMilli();
                } else if (job.getNextRunAt() != null) {
                    score = job.getNextRunAt().toEpochMilli();
                } else {
                    ops.unwatch();
                    return 0;
                }

                ops.multi();
                for (JobStatus s : JobStatus.values()) {
                    ops.opsForZSet().remove(statusIndexKey(s), jobId);
                    for (JobPriority p : JobPriority.values()) {
                        ops.opsForZSet().remove(statusPriorityIndexKey(s, p), jobId);
                    }
                }
                writeFields(ops, key, job, fields);
                applyRetention(ops, key, job);
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


