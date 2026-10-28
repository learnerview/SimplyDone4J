package io.github.learnerview.simplydone4j.service;

/**
 * How much work the workers can take on right now.
 *
 * <p>The scheduler uses this to decide how many jobs to claim. Claiming more than the
 * pool can start does not make the work happen sooner: the surplus sits in the executor's
 * queue holding a lease, and every rejected submission has to be put back on the queue
 * again. Claiming what the workers can actually start keeps the pipeline full without
 * piling up work that cannot run.</p>
 */
public interface WorkerCapacity {

    /**
     * How many jobs can start right now without waiting for a free thread.
     */
    int availableSlots();

    /**
     * How many jobs the pool runs at once.
     */
    int maxConcurrency();
}
