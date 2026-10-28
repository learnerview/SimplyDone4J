package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.springframework.data.redis.core.RedisOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a {@link JobEntity} onto its Redis hash and status indexes.
 *
 * <p>One type owns every hash field name, index key and retention rule, so the
 * repository's reads and writes cannot drift apart. The field names are the Java
 * property names, fixed by {@link PersistenceJson}; a host naming strategy cannot rename
 * the lease field out from under the fencing check.</p>
 */
final class RedisJobMapping {

    private static final List<JobStatus> TERMINAL_STATUSES = List.of(
            JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.DLQ, JobStatus.CANCELLED);

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String jobKeyPrefix;
    private final String statusIndexPrefix;
    private final String statusPriorityIndexPrefix;
    private final boolean clearPayloadOnCompletion;
    private final int ttlHours;

    RedisJobMapping(ObjectMapper objectMapper, String keyPrefix,
                    boolean clearPayloadOnCompletion, int ttlHours) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.jobKeyPrefix = keyPrefix + ":job:";
        this.statusIndexPrefix = keyPrefix + ":idx:status:";
        this.statusPriorityIndexPrefix = keyPrefix + ":idx:status:priority:";
        this.clearPayloadOnCompletion = clearPayloadOnCompletion;
        this.ttlHours = Math.max(1, ttlHours);
    }

    String jobKey(String jobId) {
        return jobKeyPrefix + jobId;
    }

    String statusIndexKey(JobStatus status) {
        return statusIndexPrefix + status.name().toLowerCase(Locale.ROOT);
    }

    String statusPriorityIndexKey(JobStatus status, JobPriority priority) {
        return statusPriorityIndexPrefix
                + status.name().toLowerCase(Locale.ROOT) + ':'
                + priority.name().toLowerCase(Locale.ROOT);
    }

    boolean isTerminal(JobStatus status) {
        return TERMINAL_STATUSES.contains(status);
    }

    long retentionSeconds() {
        return Math.max(1, ttlHours) * 3600L;
    }

    int ttlHours() {
        return ttlHours;
    }

    /**
     * Every index key a job could be a member of. The save script purges the job from all
     * of them and re-adds it to the two that match its current state.
     */
    List<String> allIndexKeys() {
        List<String> keys = new ArrayList<>();
        for (JobStatus status : JobStatus.values()) {
            keys.add(statusIndexKey(status));
        }
        for (JobStatus status : JobStatus.values()) {
            for (JobPriority priority : JobPriority.values()) {
                keys.add(statusPriorityIndexKey(status, priority));
            }
        }
        return keys;
    }

    /** Hash fields for the job with null-valued entries removed. */
    Map<String, String> fieldsFor(JobEntity job) {
        Map<String, String> fields = objectMapper.convertValue(job, STRING_MAP);
        if (clearPayloadOnCompletion && isTerminal(job.getStatus())) {
            fields.remove("payload");
        }
        fields.values().removeIf(Objects::isNull);
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
    List<String> nullFieldNames(JobEntity job) {
        Map<String, String> all = objectMapper.convertValue(job, STRING_MAP);
        List<String> names = new ArrayList<>();
        all.forEach((name, value) -> {
            if (value == null) {
                names.add(name);
            }
        });
        if (clearPayloadOnCompletion && isTerminal(job.getStatus())) {
            names.add("payload");
        }
        return names;
    }

    /** Applies a job's field map, deleting any field whose value is now null. */
    void writeFields(RedisOperations<String, String> ops, String key, JobEntity job,
                     Map<String, String> fields) {
        List<String> toDelete = nullFieldNames(job);
        if (!toDelete.isEmpty()) {
            ops.opsForHash().delete(key, toDelete.toArray());
        }
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
     * zombie member on the priority ZSET pointing at a job that no longer exists.</p>
     */
    void applyRetention(RedisOperations<String, String> ops, String key, JobEntity job) {
        if (isTerminal(job.getStatus())) {
            ops.expire(key, Duration.ofHours(ttlHours));
        } else {
            ops.persist(key);
        }
    }

    /**
     * Keeps the status indexes consistent with the job's current state. Runs either against
     * the template directly or as queued commands inside a {@code MULTI} block, and it
     * mirrors what the atomic save script does.
     */
    void applyIndexWrites(RedisOperations<String, String> ops, JobEntity job) {
        String jobId = job.getId();

        // Purge from every index the job could be a member of, so a job never lingers as a
        // zombie in an index it has left (indexes carry no TTL). Iterating ALL statuses and
        // ALL priorities is deliberate: a job requeued out of DLQ must leave
        // idx:status:dlq, and a job whose priority changed must leave its old priority.
        for (JobStatus status : JobStatus.values()) {
            ops.opsForZSet().remove(statusIndexKey(status), jobId);
            for (JobPriority priority : JobPriority.values()) {
                ops.opsForZSet().remove(statusPriorityIndexKey(status, priority), jobId);
            }
        }

        if (isTerminal(job.getStatus())) {
            // Terminal jobs are indexed by completion time. The index is shared by every
            // job in that state, so it cannot carry a per-member TTL: expiry is applied by
            // the retention sweeper, which removes members whose score is outside the
            // retention window.
            ops.opsForZSet().add(statusIndexKey(job.getStatus()), jobId, terminalScore(job));
            return;
        }

        double score = indexScore(job);
        ops.opsForZSet().add(statusIndexKey(job.getStatus()), jobId, score);
        if (job.getPriority() != null) {
            ops.opsForZSet().add(statusPriorityIndexKey(job.getStatus(), job.getPriority()),
                    jobId, score);
        }
    }

    /**
     * Ordering value for a finished job. Terminal members are only ever counted or
     * listed, never selected by a time window, so the completion time is enough to
     * keep the ZSET sensibly ordered for debugging.
     */
    double terminalScore(JobEntity job) {
        if (job.getUpdatedAt() != null) {
            return job.getUpdatedAt().toEpochMilli();
        }
        if (job.getNextRunAt() != null) {
            return job.getNextRunAt().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    double indexScore(JobEntity job) {
        if (job.getStatus() == JobStatus.RUNNING && job.getVisibleAt() != null) {
            return job.getVisibleAt().toEpochMilli();
        }
        if (job.getNextRunAt() != null) {
            return job.getNextRunAt().toEpochMilli();
        }
        return System.currentTimeMillis();
    }

    /** The score a claimed job should carry in the RUNNING index, or {@code null} if none. */
    Double runningScore(JobEntity job, java.time.Instant visibleUntil) {
        if (job.getStatus() == JobStatus.RUNNING && visibleUntil != null) {
            return (double) visibleUntil.toEpochMilli();
        }
        if (job.getNextRunAt() != null) {
            return (double) job.getNextRunAt().toEpochMilli();
        }
        return null;
    }

    JobEntity toEntity(Map<Object, Object> entries) {
        Map<String, String> stringMap = new HashMap<>();
        entries.forEach((k, v) -> stringMap.put((String) k, (String) v));
        return objectMapper.convertValue(stringMap, JobEntity.class);
    }
}
