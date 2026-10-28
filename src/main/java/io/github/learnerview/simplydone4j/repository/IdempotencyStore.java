package io.github.learnerview.simplydone4j.repository;

import java.util.Optional;

/**
 * The namespace that maps an idempotency key to the job it produced.
 *
 * <p>This is the only place that knows how the keys are named and how long they live.
 * Two owners of that namespace is not a style question: the submission path claims a key
 * and the read path looks it up, and if the two disagree about the name, a duplicate
 * submission is accepted and the work runs twice. Keeping the naming in one class makes
 * that impossible by construction.</p>
 *
 * <p>A key is claimed in two steps. {@link #claim} reserves it for a job id while that
 * job is still being persisted and enqueued; {@link #commit} turns the reservation into
 * the permanent mapping. A duplicate that arrives between those steps must not be handed
 * the reserved job id, because the original submission may still fail and roll it back.
 * Until the commit, {@link #lookup} deliberately reports nothing.</p>
 *
 * <p>The key outlives the job record on purpose. A job past its retention window is
 * gone, but the key still says "this submission was accepted", and the producer must not
 * be able to resubmit it and have the work run again.</p>
 */
public interface IdempotencyStore {

    /**
     * A reservation returned by {@link #claim}.
     *
     * @param jobId the job id the key points at
     * @param pending whether the original submission has not committed the key yet
     */
    record Reservation(String jobId, boolean pending) {
    }

    /**
     * Reserves the key for {@code jobId}.
     *
     * @return empty when this call reserved the key; otherwise the existing reservation.
     *         A pending reservation means another submission is still in progress and its
     *         job id must not be returned to a caller yet.
     */
    Optional<Reservation> claim(String producer, String idempotencyKey, String jobId);

    /**
     * Promotes a pending reservation to the permanent mapping.
     *
     * <p>The write is a compare-and-set: it only succeeds while the key still holds the
     * reservation for {@code jobId}. If the reservation expired and another submission
     * took the key, this returns {@code false} and the caller must not treat the key as
     * its own.</p>
     *
     * @return {@code true} if the key now maps to {@code jobId}.
     */
    boolean commit(String producer, String idempotencyKey, String jobId);

    /**
     * Releases the key, but only if it still points at {@code jobId}.
     *
     * <p>The compare-and-delete matters: a concurrent resubmission that re-created the
     * key must not have its lock deleted from under it.</p>
     *
     * @return {@code true} if the key was released by this call.
     */
    boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId);

    /**
     * The committed job id the key points at, if the key has been committed.
     *
     * <p>A pending reservation is not visible here: it is not yet safe to hand that job
     * id to another caller.</p>
     */
    Optional<String> lookup(String producer, String idempotencyKey);
}
