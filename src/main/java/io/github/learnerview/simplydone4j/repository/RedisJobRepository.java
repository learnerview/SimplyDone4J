package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.util.RedisKeyCodec;
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
    private final ObjectMapper fieldMapper;
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
        this.fieldMapper = objectMapper.copy()
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
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
        // ZSET on every terminal write (countByStatus read 0 and operators could not list
        // dead-lettered jobs for manual recovery) and wiped every execution log immediately
        // after writing it, while applyRetention's own Math.max kept the job hash alive.
        // Clamping once makes it impossible for the three to disagree.
        long configuredTtlHours = ((long) props.getTtlDays() * 24L) + props.getTtlHours();
        this.ttlHours = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, configuredTtlHours));
    }

    @Override
    public void save(JobEntity job) {
        String key = jobKey(job.getId());
        Map<String, String> fields = fieldsFor(job);
        redis.execute(new SessionCallback<Void>() {
            @Override
            public <K, V> Void execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                ops.multi();
                writeFields(ops, key, job, fields);
                applyIndexWrites(ops, job);
                applyRetention(ops, key, job);
                ops.exec();
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveIfLeaseHeld(JobEntity job, String expectedLeaseToken) {
        return writeIfLeaseHeld(job, expectedLeaseToken, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveIfLeaseHeldOverdue(JobEntity job, String expectedLeaseToken, Instant notAfter) {
        if (notAfter == null) {
            return writeIfLeaseHeld(job, expectedLeaseToken, null);
        }
        return writeIfLeaseHeld(job, expectedLeaseToken, notAfter);
    }

    private boolean writeIfLeaseHeld(JobEntity job, String expectedLeaseToken, Instant notAfter) {
        if (expectedLeaseToken == null) return false;

        for (int attempt = 0; attempt < FENCED_WRITE_ATTEMPTS; attempt++) {
            Integer outcome = redis.execute(new SessionCallback<Integer>() {
                // SessionCallback declares execute(RedisOperations<K,V>), so the generic
                // signature is fixed by the framework. Every command issued here targets
                // this job's String->String hash, so narrow once and then work typed.
            @Override
            public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // See saveIfLeaseHeld: narrow the framework's generic signature once.
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                    String key = jobKey(job.getId());
                    ops.watch(key);

                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    if (entries.isEmpty()) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }
                    Map<String, String> stringMap = new HashMap<>();
                    entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
                    JobEntity persisted;
                    try {
                        persisted = objectMapper.convertValue(stringMap, JobEntity.class);
                    } catch (RuntimeException e) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    if (persisted.getLeaseToken() == null
                            || !expectedLeaseToken.equals(persisted.getLeaseToken())) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    if (notAfter != null) {
                        // Reject the write when the lease has been renewed past the
                        // deadline the caller observed. The persisted hash keeps the token
                        // valid across a renewal, so the token comparison above alone would
                        // fail a live job whose lease the renewer just extended.
                        Instant persistedDeadline = persisted.getVisibleAt();
                        if (persistedDeadline == null || persistedDeadline.isAfter(notAfter)) {
                            ops.unwatch();
                            return LEASE_LOST;
                        }
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

    /**
     * {@inheritDoc}
     *
     * <p>Compares the persisted status and writes the replacement fields in one
     * {@code WATCH}/{@code MULTI}/{@code EXEC} transaction, so replay cannot leave a
     * half-reset QUEUED job behind if the process dies mid-update.</p>
     */
    @Override
    public boolean saveIfStatus(JobEntity job, JobStatus expectedStatus) {
        if (job == null || job.getId() == null || expectedStatus == null) {
            return false;
        }
        for (int attempt = 0; attempt < FENCED_WRITE_ATTEMPTS; attempt++) {
            Integer outcome = redis.execute(new SessionCallback<Integer>() {
                @Override
                public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> ops =
                            (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                    String key = jobKey(job.getId());
                    ops.watch(key);

                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    if (entries.isEmpty()) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    Map<String, String> stringMap = new HashMap<>();
                    entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
                    JobEntity persisted;
                    try {
                        persisted = objectMapper.convertValue(stringMap, JobEntity.class);
                    } catch (RuntimeException e) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    if (persisted.getStatus() != expectedStatus) {
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
            if (outcome == LEASE_LOST) {
                return false;
            }
            if (outcome == WRITTEN) {
                return true;
            }
        }
        log.warn("Gave up replacing job {} after {} attempts due to concurrent modification",
                job.getId(), FENCED_WRITE_ATTEMPTS);
        return false;
    }

    @Override
    public boolean extendLease(String jobId, String expectedLeaseToken, Instant visibleUntil) {
        if (expectedLeaseToken == null || visibleUntil == null) return false;

        for (int attempt = 0; attempt < FENCED_WRITE_ATTEMPTS; attempt++) {
            Integer outcome = redis.execute(new SessionCallback<Integer>() {
                @Override
                public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> ops =
                            (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                    String key = jobKey(jobId);
                    ops.watch(key);

                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    if (entries.isEmpty()) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    Map<String, String> stringMap = new HashMap<>();
                    entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
                    JobEntity job = objectMapper.convertValue(stringMap, JobEntity.class);

                    if (job.getStatus() != JobStatus.RUNNING
                            || !expectedLeaseToken.equals(job.getLeaseToken())) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    job.setVisibleAt(visibleUntil);
                    job.setUpdatedAt(Instant.now());

                    Map<String, String> fields = fieldsFor(job);
                    ops.multi();
                    writeFields(ops, key, job, fields);
                    applyIndexWrites(ops, job);
                    applyRetention(ops, key, job);

                    List<Object> execResult = ops.exec();
                    return execResult != null && !execResult.isEmpty()
                            ? WRITTEN : TRANSACTION_ABORTED;
                }
            });
            if (outcome == null) {
                return false;
            }
            if (outcome == WRITTEN) {
                return true;
            }
            if (outcome == LEASE_LOST) {
                return false;
            }
        }
        log.warn("Gave up extending lease for job {} after {} attempts due to concurrent modification",
                jobId, FENCED_WRITE_ATTEMPTS);
        return false;
    }

    private Map<String, String> fieldsFor(JobEntity job) {
        Map<String, String> fields = fieldMapper.convertValue(job, new TypeReference<>() {});
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
     * the lease and {@code nextRunAt}, or an operator's manual requeue clearing terminal
     * fields -- therefore appeared to succeed while the stale values survived, and a
     * requeued job still reported {@code result = "Max retries exceeded"} alongside a
     * stale {@code leaseToken} that a zombie worker from the dead run could still match.
     * Collecting the null-valued field names and issuing {@code HDEL} for them is what
     * makes clearing actually clear.
     */
    private List<String> nullFieldNames(JobEntity job, Map<String, String> fields) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null) {
                names.add(field.getKey());
            }
        }
        if (clearPayloadOnCompletion && TERMINAL_STATUSES.contains(job.getStatus())
                && !names.contains("payload")) {
            names.add("payload");
        }
        return names;
    }

    /** Applies a job's field map, deleting any field whose value is now null. */
    private void writeFields(RedisOperations<String, String> ops, String key, JobEntity job, Map<String, String> fields) {
        List<String> toDelete = nullFieldNames(job, fields);
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
        // countByStatus(DLQ) never decreased and operators kept seeing already-requeued
        // jobs in the dead-letter list.
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
            // at zero, so status-count reads under-reported every finished job.
            // Bounded by giving the index key the same retention TTL as the job record,
            // so members disappear exactly when the jobs they point at do.
            double terminalScore = terminalScore(job);
            ops.opsForZSet().add(statusIndexKey(job.getStatus()), jobId, terminalScore);
            ops.expire(statusIndexKey(job.getStatus()), Duration.ofHours(ttlHours));
            if (job.getPriority() != null) {
                String priorityKey = statusPriorityIndexKey(job.getStatus(), job.getPriority());
                ops.opsForZSet().add(priorityKey, jobId, terminalScore);
                ops.expire(priorityKey, Duration.ofHours(ttlHours));
            }
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
    /**
     * Removes terminal index members scored older than the retention window.
     *
     * <p>Terminal members are scored by last update and the job hashes they point at
     * expire after the same window, so older members are dangling pointers. The job
     * record deletes itself via its own TTL; this is how the indexes stay consistent
     * with that without a background sweeper. Called on read paths because the write
     * paths keep the {@code WATCH}/{@code MULTI} transaction small.
     */
    private void pruneTerminalIndexes() {
        double cutoff = System.currentTimeMillis() - Duration.ofHours(ttlHours).toMillis();
        for (JobStatus s : TERMINAL_STATUSES) {
            redis.opsForZSet().removeRangeByScore(statusIndexKey(s), 0.0d, cutoff);
            for (JobPriority p : JobPriority.values()) {
                redis.opsForZSet().removeRangeByScore(statusPriorityIndexKey(s, p), 0.0d, cutoff);
            }
        }
    }

    private double terminalScore(JobEntity job) {
        if (job.getUpdatedAt() != null) {
            return Math.max(0L, job.getUpdatedAt().toEpochMilli());
        }
        if (job.getNextRunAt() != null) {
            return Math.max(0L, job.getNextRunAt().toEpochMilli());
        }
        return System.currentTimeMillis();
    }

    private double indexScore(JobEntity job) {
        if (job.getStatus() == JobStatus.RUNNING && job.getVisibleAt() != null) {
            return Math.max(0L, job.getVisibleAt().toEpochMilli());
        }
        if (job.getNextRunAt() != null) {
            return Math.max(0L, job.getNextRunAt().toEpochMilli());
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
        String stored = redis.opsForValue().get(idempotencyKey(producer, idempotencyKey));
        if (stored == null) return Optional.empty();
        // A pending reservation stores `pending:<jobId>` until the submitting worker
        // promotes it; the job it names either already exists (this read path resolves it)
        // or is being created right now, in which case the caller retries.
        String jobId = stored.startsWith("pending:") ? stored.substring("pending:".length()) : stored;
        return findById(jobId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the job hash and purges the id from every status index. Leaving a member
     * behind would make {@code countByStatus} and {@code findByStatus} report a job whose
     * record no longer exists.
     */
    @Override
    public void delete(String jobId) {
        redis.execute(new SessionCallback<Void>() {
            @Override
            public <K, V> Void execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                ops.multi();
                ops.delete(jobKey(jobId));
                for (JobStatus s : JobStatus.values()) {
                    ops.opsForZSet().remove(statusIndexKey(s), jobId);
                    for (JobPriority p : JobPriority.values()) {
                        ops.opsForZSet().remove(statusPriorityIndexKey(s, p), jobId);
                    }
                }
                ops.exec();
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses the same WATCH/MULTI/EXEC compare-and-write as {@link #saveIfLeaseHeld}: the
     * status is compared and, only on a match, the new status plus the derived index moves
     * are committed in one transaction. {@code EXEC} returning {@code null} means a
     * concurrent writer touched the key, which is a loss for this caller — the losing
     * cancel must not clobber the worker's {@code RUNNING} transition.
     */
    @Override
    public boolean transitionStatusIfCurrent(String jobId, JobStatus expected, JobStatus target) {
        if (expected == null || target == null) return false;
        for (int attempt = 0; attempt < FENCED_WRITE_ATTEMPTS; attempt++) {
            Integer outcome = redis.execute(new SessionCallback<Integer>() {
                @Override
                public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                    // SessionCallback fixes the generic signature; every command addresses this
                    // job's String->String hash, so narrow once and work typed.
                    @SuppressWarnings("unchecked")
                    RedisOperations<String, String> ops =
                            (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                    String key = jobKey(jobId);
                    ops.watch(key);

                    Map<Object, Object> entries = ops.opsForHash().entries(key);
                    if (entries.isEmpty()) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    Map<String, String> stringMap = new HashMap<>();
                    entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
                    JobEntity job = objectMapper.convertValue(stringMap, JobEntity.class);

                    if (job.getStatus() != expected) {
                        ops.unwatch();
                        return LEASE_LOST;
                    }

                    job.setStatus(target);
                    job.setUpdatedAt(Instant.now());

                    Map<String, String> fields = fieldsFor(job);
                    ops.multi();
                    writeFields(ops, key, job, fields);
                    applyIndexWrites(ops, job);
                    applyRetention(ops, key, job);

                    List<Object> execResult = ops.exec();
                    return execResult != null && !execResult.isEmpty()
                            ? WRITTEN : TRANSACTION_ABORTED;
                }
            });
            if (outcome == null) {
                return false;
            }
            if (outcome == WRITTEN) {
                return true;
            }
            if (outcome == LEASE_LOST) {
                return false;
            }
        }
        log.warn("Gave up transitioning job {} from {} to {} after {} attempts due to concurrent modification",
                jobId, expected, target, FENCED_WRITE_ATTEMPTS);
        return false;
    }

    @Override
    public List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit) {
        Set<String> jobIds = redis.opsForZSet().rangeByScore(statusIndexKey(status), 0, before.toEpochMilli(), 0, limit);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream().map(this::findById).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
    }

    @Override
    public long countByStatus(JobStatus status) {
        pruneTerminalIndexes();
        Long size = redis.opsForZSet().zCard(statusIndexKey(status));
        return size != null ? size : 0L;
    }

    @Override
    public long countByStatusAndPriority(JobStatus status, JobPriority priority) {
        pruneTerminalIndexes();
        Long size = redis.opsForZSet().zCard(statusPriorityIndexKey(status, priority));
        return size != null ? size : 0L;
    }

    @Override
    public int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil,
                                  Instant now, JobStatus fromStatus, JobStatus toStatus) {
        Integer updated = redis.execute(new SessionCallback<Integer>() {
            // See saveIfLeaseHeld: SessionCallback fixes the generic signature, so narrow
            // once here. Every command below targets this job's String->String hash.
            @Override
            public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // SessionCallback fixes this signature as generic. Every command here
                // addresses this job's String->String hash, so narrow once via cast and
                // then work in typed operations throughout.
                @SuppressWarnings("unchecked")
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
                    score = Math.max(0L, visibleUntil.toEpochMilli());
                } else if (job.getNextRunAt() != null) {
                    score = Math.max(0L, job.getNextRunAt().toEpochMilli());
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
        return updated != null ? updated : 0;
    }

    @Override
    public List<JobEntity> findByProducerAndStatus(String producer, JobStatus status) {
        return findByStatus(status).stream()
                .filter(j -> producer.equals(j.getProducer()))
                .collect(Collectors.toList());
    }

    @Override
    public List<JobEntity> findByStatus(JobStatus status) {
        return findByStatus(status, Integer.MAX_VALUE);
    }

    @Override
    public List<JobEntity> findByStatus(JobStatus status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (TERMINAL_STATUSES.contains(status)) {
            pruneTerminalIndexes();
        }
        Set<String> jobIds = redis.opsForZSet().range(statusIndexKey(status), 0, limit - 1L);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream().map(this::findById).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
    }

    private String jobKey(String jobId) { return jobKeyPrefix + jobId; }
    private String statusIndexKey(JobStatus status) { return statusIndexPrefix + status.name().toLowerCase(); }
    private String statusPriorityIndexKey(JobStatus status, JobPriority priority) { return statusPriorityIndexPrefix + status.name().toLowerCase() + ':' + priority.name().toLowerCase(); }
    private String idempotencyKey(String producer, String idempotencyKey) {
        return RedisKeyCodec.idempotencyKey(idempotencyPrefix, producer, idempotencyKey);
    }
}
