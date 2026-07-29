package io.github.learnerview.simplydone4j.exception;

public final class QueueFullException extends RuntimeException {
    public QueueFullException(long maxDepth) {
        super("Queue is full (max depth: " + maxDepth + ')');
    }
}
