package io.github.learnerview.simplydone4j.service;

import java.time.Duration;

/**
 * Mutual exclusion for jobs that must not overlap.
 *
 * <p>Two instances of the same recurring or retried job can easily be in flight at once: a
 * slow run has not finished when the next one comes due, a retry is promoted while the
 * original attempt is still blocked on a network call, or a user double-clicks. For work
 * like "reconcile account 42" or "send invoice 42" that overlap is a correctness bug, not
 * a performance one.</p>
 *
 * <p>Rather than a per-job-type switch, a submission can carry a {@code uniqueKey}. Any
 * two jobs sharing a key are guaranteed never to run at the same time across the whole
 * cluster, because the guard lives in Redis rather than in a worker's heap. Jobs without
 * a key are unaffected and pay nothing.</p>
 *
 * <p>The lock is held with a TTL rather than released on a schedule, so a worker that is
 * killed mid-job cannot wedge the key forever. It is still released eagerly in a
 * {@code finally} block on the normal path, which keeps the key available immediately
 * rather than after the full TTL.</p>
 */
public interface UniquenessGuard {

    /** No uniqueness enforcement. Every acquisition succeeds and releasing is a no-op. */
    UniquenessGuard DISABLED = new UniquenessGuard() {
        @Override public boolean isEnabled() { return false; }
        @Override public boolean tryAcquire(String uniqueKey, String owner, Duration ttl) { return true; }
        @Override public boolean release(String uniqueKey, String owner) { return true; }
    };

    boolean isEnabled();

    /**
     * Attempts to take the key for {@code ttl}.
     *
     * <p>Acquisition is exclusive, not reentrant: a key already held -- even by the same
     * owner -- reports contention. Reentrancy is not needed, because no caller ever
     * tries to take the same key twice in one run, and silently "succeeding" on a
     * held key would let two overlapping runs of the same job through.</p>
     *
     * @return true if this call took the key; false if another owner holds it
     */
    boolean tryAcquire(String uniqueKey, String owner, Duration ttl);

    /**
     * Releases the key, but only if this owner still holds it.
     *
     * <p>The owner check is not a nicety. Without it, a job whose TTL has already lapsed
     * would release a lock that a different job has since legitimately taken, and the two
     * would then run together -- silently defeating the guarantee this interface exists
     * to provide.</p>
     *
     * @return true if the key was released by this call
     */
    boolean release(String uniqueKey, String owner);
}
