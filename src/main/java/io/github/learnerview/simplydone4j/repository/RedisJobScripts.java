package io.github.learnerview.simplydone4j.repository;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Lua scripts that make the multi-key job writes atomic.
 *
 * <p>Kept in their own type so the repository reads as Java and the scripts read as
 * scripts. Redis executes a script without interleaving another client, which is the
 * property the lease checks and the hash/index writes depend on.</p>
 */
final class RedisJobScripts {

    private RedisJobScripts() {
    }

    /**
     * Renews a running job's lease in one atomic round trip.
     *
     * <p>Two things have to happen together: the lease deadline stored on the job hash and
     * the score of the job's member in the {@code RUNNING} status index. The reaper selects
     * reclaimable jobs with {@code ZRANGEBYSCORE} on that index, so a renewed deadline that
     * did not move the score would leave the job looking expired and the reaper would
     * reclaim a job that is still running.</p>
     *
     * <p>The script compares the persisted lease token before writing anything, so a worker
     * that already lost its lease cannot extend somebody else's.</p>
     *
     * <p>{@code ARGV}: lease token, job id, new visible-at (epoch millis), new visible-at
     * (ISO-8601), updated-at (epoch millis), lease-token field, visible-at field,
     * updated-at field.</p>
     */
    static final RedisScript<Long> EXTEND_LEASE = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], ARGV[6]) ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[7], ARGV[4], ARGV[8], ARGV[5])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[2])
            return 1
            """, Long.class);

    /**
     * Cancels a job, or records the request, in one atomic step.
     *
     * <p>A cancellation is a compare-and-set on the job's status plus the index moves that
     * belong to it. Doing the comparison in application memory and writing afterwards
     * leaves a window in which a worker claims the job and the cancel overwrites the
     * worker's {@code RUNNING} transition: the job is reported cancelled while it is
     * still executing. Running the whole thing inside one script closes that window.</p>
     *
     * <p>{@code ARGV}: observed status, job id, cancelled-by, status field, completed-at
     * field, result field, cancel-requested field, cancelled-by field, updated-at field,
     * visible-at field, lease-token field, lease-owner field, next-run-at field,
     * completed-at (ISO-8601), now (epoch millis), retention seconds.</p>
     */
    static final RedisScript<Long> CANCEL = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], ARGV[4])
            if not status then
                return -1
            end
            if status == 'RUNNING' then
                redis.call('HSET', KEYS[1], ARGV[7], 'true', ARGV[8], ARGV[3], ARGV[9], ARGV[14])
                return 2
            end
            if status == 'SUCCESS' or status == 'FAILED' or status == 'DLQ' or status == 'CANCELLED' then
                return 0
            end
            if status ~= ARGV[1] then
                return 0
            end
            redis.call('HSET', KEYS[1], ARGV[4], 'CANCELLED', ARGV[5], ARGV[14], ARGV[6], 'Cancelled by ' .. ARGV[3], ARGV[9], ARGV[14])
            redis.call('HDEL', KEYS[1], ARGV[10], ARGV[11], ARGV[12], ARGV[13])
            redis.call('ZREM', KEYS[2], ARGV[2])
            if KEYS[3] ~= '' then
                redis.call('ZREM', KEYS[3], ARGV[2])
            end
            redis.call('ZADD', KEYS[5], ARGV[15], ARGV[2])
            if KEYS[6] ~= '' then
                redis.call('ZADD', KEYS[6], ARGV[15], ARGV[2])
            end
            redis.call('EXPIRE', KEYS[1], ARGV[16])
            return 1
            """, Long.class);

    /**
     * Writes a job and every index it belongs to in one atomic step.
     *
     * <p>The old shape was nineteen round trips: two for the hash, one per index the job
     * was leaving, one per index it was joining, one for the TTL. A failure in the middle
     * of that left the record and the indexes disagreeing -- a job that says {@code SUCCESS}
     * while still sitting in the ready queue, or a job that has left {@code idx:status:dlq}
     * but is still in the dead-letter count. Nothing could repair that, because the write
     * that would have repaired it is the write that failed.</p>
     *
     * <p>Inside a script the whole write is one step. The script is also the place where
     * the lease check belongs: comparing the token and writing the record in the same
     * script closes the window that the {@code WATCH}/{@code MULTI} version had between
     * the check and the write.</p>
     *
     * <p>Keys: the job hash, the status index it joins, the status+priority index it
     * joins (empty when it has no priority) and then every index it could still be a
     * member of. The purge is deliberately wider than "the indexes it is leaving": a job
     * whose priority changed kept its old priority's member, and a job requeued out of
     * the dead letter state kept its dead-letter member.</p>
     */
    static final RedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local jobKey = KEYS[1]
            local statusIndexKey = KEYS[2]
            local statusPriorityIndexKey = KEYS[3]

            local jobId = ARGV[1]
            local expectedLeaseToken = ARGV[2]
            local leaseTokenField = ARGV[3]

            -- The type of a key, in a form that works on every supported Redis: Redis 7
            -- returns a table from TYPE inside a script, earlier versions a string.
            local function keyType(key)
                local t = redis.call('TYPE', key)
                if type(t) == 'table' then
                    return t['ok']
                end
                return t
            end

            -- A Redis script has no rollback. An error half way through leaves the writes
            -- that already happened in place, so a job could end up with a new status and
            -- no index entry, or the other way round. Checking the types of everything the
            -- write touches turns that half-applied write into a refused one: nothing is
            -- written at all.
            local jobKeyType = keyType(jobKey)
            if jobKeyType ~= 'none' and jobKeyType ~= 'hash' then
                return -1
            end
            for i = 4, #KEYS do
                local t = keyType(KEYS[i])
                if t ~= 'none' and t ~= 'zset' then
                    return -1
                end
            end
            if statusIndexKey ~= '' then
                local t = keyType(statusIndexKey)
                if t ~= 'none' and t ~= 'zset' then
                    return -1
                end
            end
            if statusPriorityIndexKey ~= '' then
                local t = keyType(statusPriorityIndexKey)
                if t ~= 'none' and t ~= 'zset' then
                    return -1
                end
            end

            -- The fence. An empty token means the write is not fenced, which is the
            -- plain save path; anything else has to match the persisted token or the
            -- write does not happen at all.
            if expectedLeaseToken ~= '' then
                local persisted = redis.call('HGET', jobKey, leaseTokenField)
                if persisted ~= expectedLeaseToken then
                    return 0
                end
            end

            local pos = 4
            local fieldCount = tonumber(ARGV[pos])
            pos = pos + 1
            if fieldCount > 0 then
                local pairs = {}
                for i = 1, fieldCount do
                    pairs[#pairs + 1] = ARGV[pos]
                    pairs[#pairs + 1] = ARGV[pos + 1]
                    pos = pos + 2
                end
                redis.call('HSET', jobKey, unpack(pairs))
            end

            -- A Redis hash write cannot express "set this to null", so the fields that
            -- are now null are deleted rather than written.
            local deleteCount = tonumber(ARGV[pos])
            pos = pos + 1
            if deleteCount > 0 then
                local names = {}
                for i = 1, deleteCount do
                    names[#names + 1] = ARGV[pos]
                    pos = pos + 1
                end
                redis.call('HDEL', jobKey, unpack(names))
            end

            local retentionMode = ARGV[pos]
            local retentionSeconds = tonumber(ARGV[pos + 1])
            local score = tonumber(ARGV[pos + 2])
            local terminal = ARGV[pos + 3]

            -- Purge first, then re-add. The job is removed from every index it could be
            -- a member of, so it can only ever be a member of the two that match its
            -- current state.
            for i = 4, #KEYS do
                redis.call('ZREM', KEYS[i], jobId)
            end

            redis.call('ZADD', statusIndexKey, score, jobId)
            -- A finished job is counted and listed, never selected by a time window, so
            -- it is indexed by status only.
            if statusPriorityIndexKey ~= '' and terminal ~= '1' then
                redis.call('ZADD', statusPriorityIndexKey, score, jobId)
            end

            if retentionMode == 'EXPIRE' then
                redis.call('EXPIRE', jobKey, retentionSeconds)
            else
                redis.call('PERSIST', jobKey)
            end

            return 1
            """, Long.class);
}
