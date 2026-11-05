package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.model.EngineStatus;

/**
 * Checks Redis connectivity and the state of the engine's background loops.
 *
 * <p>The check is pull-based so applications stay in control of how often it runs and how
 * failures are reported. It performs a Redis {@code PING} on every call.</p>
 */
public interface EngineStatusService {

    EngineStatus status();

    default boolean isHealthy() {
        return status().healthy();
    }
}
