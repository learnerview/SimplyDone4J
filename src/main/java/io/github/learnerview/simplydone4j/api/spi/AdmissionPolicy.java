package io.github.learnerview.simplydone4j.api.spi;

/**
 * Decides whether a submission is admitted.
 *
 * <p>This is a policy hook, and the library does not implement one. Rate limits, quotas,
 * per-tenant tiers and "is this producer allowed to do this" are decisions an
 * application makes, and an application usually already makes them: at its gateway, in
 * its service mesh, in its own {@code Bucket4j} bucket. A queue that also enforces them
 * forces every application to either accept the library's limits or to disable them, and
 * it puts the decision in the wrong place -- the queue is downstream of the decision, not
 * upstream of it.</p>
 *
 * <p>What the library keeps is the backpressure that belongs to a queue: its depth. A
 * submission that arrives when the queues are full is rejected with
 * {@code QueueFullException}, and that limit is about the queue's own capacity rather
 * than about the producer's quota.</p>
 *
 * <p>The default admits everything. Implementations must be fast and must not block:
 * the call sits on the submission path.</p>
 */
@FunctionalInterface
public interface AdmissionPolicy {

    /**
     * Decides whether the submission is admitted.
     *
     * <p>Throwing from this method is allowed and the exception propagates to the
     * caller unchanged, which is how a policy that is implemented on top of another
     * library reports its own failures. A policy that wants to reject should return
     * {@link AdmissionDecision#reject} instead, so the caller gets the library's
     * typed exception.</p>
     */
    AdmissionDecision decide(AdmissionRequest request);

    /**
     * The policy that admits everything: the default when the application has no policy
     * of its own.
     */
    static AdmissionPolicy allowAll() {
        return request -> AdmissionDecision.allow();
    }
}
