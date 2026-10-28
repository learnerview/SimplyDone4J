package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Redis-backed job store.
 *
 * <p>This type owns reads, queries and state transitions; the hash/index mapping lives in
 * {@link RedisJobMapping} and the multi-key writes in {@link RedisJobWriter}. The
 * transitions that must not interleave with a worker's write -- cancellation and claiming
 * -- are single Lua scripts, because comparing a status in Java and writing afterwards
 * leaves a window for the other side to win.</p>
 */
public final class RedisJobRepository implements JobRepository {

    private final StringRedisTemplate redis;
    private final RedisJobMapping mapping;
    private final RedisJobWriter writer;

    public RedisJobRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        this.redis = redis;
        // One retention window, floored at an hour, shared by the job hash, the terminal
        // status index and the execution log. A raw zero reaches Redis as `EXPIRE key 0`,
        // which DELETES the key instead of expiring it, so clamping happens once here.
        int ttlHours = Math.max(1, (props.getTtlDays() * 24) + props.getTtlHours());
        this.mapping = new RedisJobMapping(PersistenceJson.mapper(), props.getKeyPrefix(),
                props.getRetention().isClearPayloadOnCompletion(), ttlHours);
        this.writer = new RedisJobWriter(redis, mapping);
    }

    @Override
    public void save(JobEntity job) {
        writer.save(job);
    }

    @Override
    public boolean saveIfLeaseHeld(JobEntity job, String expectedLeaseToken) {
        return writer.saveIfLeaseHeld(job, expectedLeaseToken);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The new deadline is written in ISO-8601, which every Jackson configuration reads
     * back into an {@code Instant} whether or not it is configured to write dates as
     * timestamps. The job's member in the RUNNING index moves with it in the same
     * script, or the reaper would see an expired deadline that the renewal claimed to
     * have extended.</p>
     */
    @Override
    public boolean extendLease(String jobId, String expectedLeaseToken, Instant newVisibleUntil) {
        if (jobId == null || expectedLeaseToken == null || newVisibleUntil == null) return false;

        long visibleAtMillis = newVisibleUntil.toEpochMilli();
        long updatedAtMillis = System.currentTimeMillis();
        String visibleAt = DateTimeFormatter.ISO_INSTANT.format(newVisibleUntil);

        List<String> keys = List.of(
                mapping.jobKey(jobId),
                mapping.statusIndexKey(JobStatus.RUNNING));

        List<String> args = List.of(
                expectedLeaseToken,
                jobId,
                String.valueOf(visibleAtMillis),
                visibleAt,
                String.valueOf(updatedAtMillis),
                "leaseToken",
                "visibleAt",
                "updatedAt");

        Long result = redis.execute(RedisJobScripts.EXTEND_LEASE, keys, args.toArray());
        return result != null && result == 1L;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two fields, one round trip, no fencing: progress is a report, not a state
     * transition.</p>
     */
    @Override
    public boolean updateProgress(String jobId, double percent, String message) {
        if (jobId == null) return false;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("progress", String.valueOf(percent));
        fields.put("progressMessage", message != null ? message : "");
        fields.put("updatedAt", String.valueOf(System.currentTimeMillis()));

        redis.opsForHash().putAll(mapping.jobKey(jobId), fields);
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The whole cancellation is one script: the status comparison, the state change
     * and the index moves cannot interleave with a worker's claim, so a job that is
     * cancelled is never executed and a job that has started is never reported as
     * cancelled while it is still running.</p>
     */
    @Override
    public CancelOutcome requestCancellation(String jobId, String cancelledBy) {
        if (jobId == null) return CancelOutcome.NOT_FOUND;

        JobEntity job = findById(jobId).orElse(null);
        if (job == null) return CancelOutcome.NOT_FOUND;

        JobStatus observedStatus = job.getStatus();
        if (observedStatus == null) return CancelOutcome.NOT_CANCELLABLE;

        Instant now = Instant.now();
        List<String> keys = new ArrayList<>(List.of(
                mapping.jobKey(jobId),
                mapping.statusIndexKey(observedStatus),
                job.getPriority() != null
                        ? mapping.statusPriorityIndexKey(observedStatus, job.getPriority())
                        : "",
                "",
                mapping.statusIndexKey(JobStatus.CANCELLED),
                job.getPriority() != null
                        ? mapping.statusPriorityIndexKey(JobStatus.CANCELLED, job.getPriority())
                        : ""));

        List<String> args = new ArrayList<>(List.of(
                observedStatus.name(),
                jobId,
                cancelledBy != null ? cancelledBy : "user",
                "status",
                "completedAt",
                "result",
                "cancelRequested",
                "cancelledBy",
                "updatedAt",
                "visibleAt",
                "leaseToken",
                "leaseOwner",
                "nextRunAt",
                DateTimeFormatter.ISO_INSTANT.format(now),
                String.valueOf(now.toEpochMilli()),
                String.valueOf(mapping.retentionSeconds())));

        Long result = redis.execute(RedisJobScripts.CANCEL, keys, args.toArray());
        if (result == null) {
            return CancelOutcome.NOT_CANCELLABLE;
        }
        return switch (result.intValue()) {
            case 1 -> CancelOutcome.CANCELLED;
            case 2 -> CancelOutcome.REQUESTED;
            case -1 -> CancelOutcome.NOT_FOUND;
            default -> CancelOutcome.NOT_CANCELLABLE;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>One field, one round trip. The worker polls this once a second for every running
     * job, so reading the whole job record would multiply the cost of a running job by
     * the size of its record.</p>
     */
    @Override
    public boolean isCancellationRequested(String jobId) {
        if (jobId == null) return false;
        Object flag = redis.opsForHash().get(mapping.jobKey(jobId), "cancelRequested");
        return flag != null && Boolean.parseBoolean(flag.toString());
    }

    @Override
    public Optional<JobEntity> findById(String jobId) {
        Map<Object, Object> entries = redis.opsForHash().entries(mapping.jobKey(jobId));
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(mapping.toEntity(entries));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the job hash and purges the id from every status index. Leaving a member
     * behind would make {@code countByStatus} and {@code findByStatus} report a job whose
     * record no longer exists.</p>
     */
    @Override
    public void delete(String jobId) {
        redis.delete(mapping.jobKey(jobId));
        for (JobStatus status : JobStatus.values()) {
            redis.opsForZSet().remove(mapping.statusIndexKey(status), jobId);
            for (JobPriority priority : JobPriority.values()) {
                redis.opsForZSet().remove(mapping.statusPriorityIndexKey(status, priority), jobId);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses the same WATCH/MULTI/EXEC compare-and-write as the fenced save: the status
     * is compared and, only on a match, the new status plus the derived index moves are
     * committed in one transaction. {@code EXEC} returning {@code null} means a
     * concurrent writer touched the key, which is a loss for this caller — the losing
     * cancel must not clobber the worker's {@code RUNNING} transition.</p>
     */
    @Override
    public boolean transitionStatusIfCurrent(String jobId, JobStatus expected, JobStatus target) {
        if (expected == null || target == null) return false;
        return Boolean.TRUE.equals(redis.execute(new SessionCallback<Boolean>() {
            @Override
            public <K, V> Boolean execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // SessionCallback fixes the generic signature; every command addresses this
                // job's String->String hash, so narrow once and work typed.
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                String key = mapping.jobKey(jobId);
                ops.watch(key);

                Map<Object, Object> entries = ops.opsForHash().entries(key);
                if (entries.isEmpty()) {
                    ops.unwatch();
                    return false;
                }

                JobEntity job = mapping.toEntity(entries);
                if (job.getStatus() != expected) {
                    ops.unwatch();
                    return false;
                }

                job.setStatus(target);
                job.setUpdatedAt(Instant.now());

                Map<String, String> fields = mapping.fieldsFor(job);
                ops.multi();
                mapping.writeFields(ops, key, job, fields);
                mapping.applyIndexWrites(ops, job);
                mapping.applyRetention(ops, key, job);

                List<Object> execResult = ops.exec();
                return execResult != null && !execResult.isEmpty();
            }
        }));
    }

    @Override
    public List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit, int offset) {
        Set<String> jobIds = redis.opsForZSet().rangeByScore(
                mapping.statusIndexKey(status), 0, before.toEpochMilli(), offset, limit);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream()
                .map(this::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Override
    public long countByStatus(JobStatus status) {
        Long size;
        if (mapping.isTerminal(status)) {
            long cutoff = System.currentTimeMillis() - mapping.retentionSeconds() * 1000L;
            size = redis.opsForZSet().count(mapping.statusIndexKey(status),
                    cutoff, Double.POSITIVE_INFINITY);
        } else {
            size = redis.opsForZSet().zCard(mapping.statusIndexKey(status));
        }
        return size != null ? size : 0L;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Terminal status indexes are shared ZSETs: a Redis TTL applies to the whole key,
     * not to one member, so expiring the index would either keep every old member alive
     * while traffic continues or delete every terminal job after a quiet period. The
     * members carry their completion time as the score, and this sweep removes the ones
     * that have fallen out of the retention window.</p>
     */
    @Override
    public long pruneTerminalIndexes() {
        long cutoff = System.currentTimeMillis() - mapping.retentionSeconds() * 1000L;
        long removed = 0;
        for (JobStatus status : List.of(JobStatus.SUCCESS, JobStatus.FAILED,
                JobStatus.DLQ, JobStatus.CANCELLED)) {
            Long count = redis.opsForZSet().removeRangeByScore(
                    mapping.statusIndexKey(status), 0, cutoff);
            if (count != null) {
                removed += count;
            }
        }
        return removed;
    }

    @Override
    public long countByStatusAndPriority(JobStatus status, JobPriority priority) {
        Long size = redis.opsForZSet().zCard(mapping.statusPriorityIndexKey(status, priority));
        return size != null ? size : 0L;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The claim is a WATCH/MULTI compare-and-write: the job must still be
     * {@code QUEUED}, and the lease, status and index moves are committed together. If a
     * concurrent writer wins, the claim reports zero and the caller treats the job as not
     * claimed.</p>
     */
    @Override
    public int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil,
                                 Instant now, JobStatus fromStatus, JobStatus toStatus) {
        return redis.execute(new SessionCallback<Integer>() {
            @Override
            public <K, V> Integer execute(RedisOperations<K, V> genericOps) throws DataAccessException {
                // SessionCallback fixes this signature as generic. Every command here
                // addresses this job's String->String hash, so narrow once via cast and
                // then work in typed operations throughout.
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops =
                        (RedisOperations<String, String>) (RedisOperations<?, ?>) genericOps;
                String key = mapping.jobKey(jobId);
                ops.watch(key);

                Map<Object, Object> entries = ops.opsForHash().entries(key);
                if (entries.isEmpty()) {
                    ops.unwatch();
                    return 0;
                }

                JobEntity job = mapping.toEntity(entries);
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

                // Resolve the score BEFORE opening the transaction. Redis rejects UNWATCH
                // inside MULTI, so bailing out after ops.multi() would leave the connection
                // in a broken state.
                if (mapping.runningScore(job, toStatus == JobStatus.RUNNING ? visibleUntil : null) == null) {
                    ops.unwatch();
                    return 0;
                }

                Map<String, String> fields = mapping.fieldsFor(job);
                ops.multi();
                mapping.writeFields(ops, key, job, fields);
                mapping.applyRetention(ops, key, job);
                mapping.applyIndexWrites(ops, job);

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
        Set<String> jobIds = redis.opsForZSet().range(mapping.statusIndexKey(status), 0, -1);
        if (jobIds == null || jobIds.isEmpty()) return List.of();
        return jobIds.stream()
                .map(this::findById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }
}
