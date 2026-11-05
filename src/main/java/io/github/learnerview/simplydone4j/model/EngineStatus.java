package io.github.learnerview.simplydone4j.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Point-in-time view of the engine's dependencies and background loops.
 *
 * <p>This is intentionally dependency-free: an application can log it, expose it from its
 * own endpoint, or turn it into an alert without adding Actuator or Micrometer.</p>
 */
public record EngineStatus(
        boolean redisReachable,
        boolean schedulerEnabled,
        boolean schedulerRunning,
        boolean maintenanceRunning,
        int workersInFlight,
        int workerCapacity,
        Map<JobPriority, Long> queueDepths) {

    public EngineStatus {
        EnumMap<JobPriority, Long> copy = new EnumMap<>(JobPriority.class);
        if (queueDepths != null) {
            copy.putAll(queueDepths);
        }
        queueDepths = Collections.unmodifiableMap(copy);
    }

    /**
     * Redis must be reachable and, when enabled, both background loops must be alive.
     * Queue depth is reported for diagnostics but does not make the engine unhealthy.
     */
    public boolean healthy() {
        return redisReachable
                && (!schedulerEnabled || (schedulerRunning && maintenanceRunning));
    }
}
