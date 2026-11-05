package io.github.learnerview.simplydone4j.service;

import java.util.Optional;

public interface IdempotencyService {
    /**
     * Attempts to acquire an idempotency reservation for the given producer and key.
     *
     * <p>Reservations are two-phase. While a submission is in flight the key holds a
     * {@code pending:<jobId>} marker; once the job record exists the marker is promoted to
     * the bare {@code jobId} by {@link #confirmReservation}. A {@code pending:} reservation
     * whose job record is missing is an in-flight submission and must not be released or
     * treated as stale.</p>
     *
     * @param producer       the producer submitting the job
     * @param idempotencyKey the idempotency key for the job
     * @param jobId          the new job ID to store if the reservation is acquired
     * @return Optional.empty() if the reservation was successfully acquired.
     *         Optional.of(reservation) if the key was already held by another job.
     */
    Optional<IdempotencyReservation> acquireOrGetExisting(String producer, String idempotencyKey, String jobId);

    /**
     * Promotes a {@code pending:<jobId>} reservation to the bare {@code jobId}.
     *
     * <p>Called after the job record has been persisted, so a duplicate submission that saw
     * the pending marker can resolve the job id against the store. Failure is not fatal to
     * the submission: the job exists, and future duplicates resolve through the pending
     * marker anyway.</p>
     *
     * @return {@code true} if the reservation was promoted (or was already promoted);
     *         {@code false} if the key no longer holds this submission's reservation.
     */
    boolean confirmReservation(String producer, String idempotencyKey, String jobId);

    /**
     * Releases an idempotency reservation, but only if it still points at {@code jobId}.
     *
     * <p>The lock and the job record have independent TTLs. If the job hash expires first
     * (a job past its retention window) while the idempotency key outlives it, the lock
     * keeps reporting a job id that no longer resolves. Without this escape hatch the
     * producer is permanently blocked from resubmitting that key. Compare-and-delete treats
     * both {@code pending:<jobId>} and {@code jobId} as owned by this submission.</p>
     *
     * @return {@code true} if the lock was released by this call.
     */
    boolean releaseIfOwnedBy(String producer, String idempotencyKey, String jobId);
}
