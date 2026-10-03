package io.github.learnerview.simplydone4j.repository;

/**
 * Issues the fencing token attached to every lease.
 *
 * <p>A fencing token must be <em>monotonic</em>, not merely unique. A random UUID
 * proves that two leases differ, but it carries no ordering: it cannot answer
 * "is this writer newer than the one that wrote before it?". Only a monotonic
 * sequence can impose a total order on epochs of ownership, which is what lets
 * a downstream store reject a late write from a superseded owner.</p>
 */
public interface FencingTokenSequence {

    /**
     * @return the next token. Tokens are strictly increasing across every
     *         application instance sharing the same backing store.
     */
    String nextToken();
}
