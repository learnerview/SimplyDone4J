package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes job records with their indexes and retention.
 *
 * <p>The write is a single Lua script. That matters beyond speed: the hash and the
 * status indexes move together or not at all, and a fenced write compares the lease
 * token in the same script that rewrites the record, so the check cannot be separated
 * from the write by another client.</p>
 *
 * <p>There is deliberately no fallback that issues the writes as separate commands. If
 * the script cannot run, the write fails closed: a partially applied job would leave a
 * hash that disagrees with its indexes, which is worse than a failed write the caller
 * can retry.</p>
 */
final class RedisJobWriter {

    private static final Logger log = LoggerFactory.getLogger(RedisJobWriter.class);

    /** The script refused to write because a key held the wrong type. */
    private static final long REFUSED = -1L;

    private final StringRedisTemplate redis;
    private final RedisJobMapping mapping;

    RedisJobWriter(StringRedisTemplate redis, RedisJobMapping mapping) {
        this.redis = redis;
        this.mapping = mapping;
    }

    void save(JobEntity job) {
        Map<String, String> fields = mapping.fieldsFor(job);
        List<String> toDelete = mapping.nullFieldNames(job);

        Long result = executeSaveScript(job, fields, toDelete, null);
        if (result == null) {
            throw new IllegalStateException("The atomic save script did not run for job "
                    + job.getId());
        }
        if (result == REFUSED) {
            throw new IllegalStateException("Refused to write job " + job.getId()
                    + ": a key the write needs is not of the expected type");
        }
    }

    /**
     * Writes the job only if {@code expectedLeaseToken} still owns it.
     *
     * @return {@code true} if the write happened; {@code false} if the lease was lost or
     *         the script could not establish ownership.
     */
    boolean saveIfLeaseHeld(JobEntity job, String expectedLeaseToken) {
        if (expectedLeaseToken == null) return false;

        Map<String, String> fields = mapping.fieldsFor(job);
        List<String> toDelete = mapping.nullFieldNames(job);

        Long result = executeSaveScript(job, fields, toDelete, expectedLeaseToken);
        if (result == null) {
            log.error("The atomic save script did not run for job {}; refusing the write",
                    job.getId());
            return false;
        }
        if (result == REFUSED) {
            log.error("Refused to write job {}: a key the write needs is not of the expected type",
                    job.getId());
            return false;
        }
        return result == 1L;
    }

    /**
     * Runs the atomic save script.
     *
     * @return {@code 1} when the job was written, {@code 0} when the lease was lost,
     *         {@code -1} when a key has the wrong type, and {@code null} when Redis
     *         returned no result for the script.
     */
    private Long executeSaveScript(JobEntity job, Map<String, String> fields, List<String> toDelete,
                                   String expectedLeaseToken) {
        List<String> keys = new ArrayList<>();
        keys.add(mapping.jobKey(job.getId()));
        keys.add(mapping.statusIndexKey(job.getStatus()));
        keys.add(job.getPriority() != null
                ? mapping.statusPriorityIndexKey(job.getStatus(), job.getPriority())
                : "");
        keys.addAll(mapping.allIndexKeys());

        List<String> args = new ArrayList<>();
        args.add(job.getId());
        args.add(expectedLeaseToken != null ? expectedLeaseToken : "");
        args.add(expectedLeaseToken != null ? "leaseToken" : "");
        args.add(String.valueOf(fields.size()));
        fields.forEach((name, value) -> {
            args.add(name);
            args.add(value);
        });
        args.add(String.valueOf(toDelete.size()));
        args.addAll(toDelete);

        boolean terminal = mapping.isTerminal(job.getStatus());
        long retentionSeconds = mapping.retentionSeconds();
        args.add(terminal ? "EXPIRE" : "PERSIST");
        args.add(String.valueOf(retentionSeconds));
        args.add(String.valueOf((long) (terminal ? mapping.terminalScore(job) : mapping.indexScore(job))));
        args.add(terminal ? "1" : "0");

        return redis.execute(RedisJobScripts.SAVE, keys, args.toArray());
    }
}
