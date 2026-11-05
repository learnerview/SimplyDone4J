package io.github.learnerview.simplydone4j.service;

/**
 * Result of an idempotency acquisition attempt.
 *
 * @param jobId   the job id the key resolves to, with any {@code pending:} prefix stripped
 * @param pending {@code true} when the reservation holder has not yet confirmed that the job
 *                record exists. A pending reservation whose job is missing is an in-flight
 *                submission, not a stale lock, and must not be released or re-acquired.
 */
public record IdempotencyReservation(String jobId, boolean pending) {
}
