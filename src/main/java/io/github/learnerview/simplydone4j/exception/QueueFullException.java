package io.github.learnerview.simplydone4j.exception;

/**
 * Thrown when the queues already hold {@code queue.max-depth} jobs in total and the
 * submission would push the backlog past it.
 */
public final class QueueFullException extends RuntimeException {
    public QueueFullException(long maxDepth) {
        super("Queue is full (max total depth across all priority queues: " + maxDepth + ')');
    }
}
