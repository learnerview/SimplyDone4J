package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.EnumMap;
import java.util.Map;

public final class EngineStatusServiceImpl implements EngineStatusService {

    private final StringRedisTemplate redis;
    private final ObjectProvider<SchedulerLoop> scheduler;
    private final ObjectProvider<WorkerMaintenanceScheduler> maintenance;
    private final QueueRepository queueRepository;
    private final WorkerCapacity workerCapacity;
    private final SimplyDoneProperties properties;

    public EngineStatusServiceImpl(StringRedisTemplate redis,
                                   ObjectProvider<SchedulerLoop> scheduler,
                                   ObjectProvider<WorkerMaintenanceScheduler> maintenance,
                                   QueueRepository queueRepository,
                                   WorkerCapacity workerCapacity,
                                   SimplyDoneProperties properties) {
        this.redis = redis;
        this.scheduler = scheduler;
        this.maintenance = maintenance;
        this.queueRepository = queueRepository;
        this.workerCapacity = workerCapacity;
        this.properties = properties;
    }

    @Override
    public EngineStatus status() {
        boolean redisReachable;
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            redisReachable = pong != null && "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException e) {
            redisReachable = false;
        }

        boolean schedulerEnabled = properties.getScheduler().isEnabled();
        SchedulerLoop loop = scheduler.getIfAvailable();
        WorkerMaintenanceScheduler maintenanceLoop = maintenance.getIfAvailable();
        boolean schedulerRunning = loop != null && loop.isRunning();
        boolean maintenanceRunning = maintenanceLoop != null && maintenanceLoop.isRunning();

        Map<JobPriority, Long> depths = new EnumMap<>(JobPriority.class);
        if (redisReachable) {
            try {
                for (JobPriority priority : JobPriority.values()) {
                    depths.put(priority, queueRepository.queueSize(priority));
                }
            } catch (RuntimeException e) {
                // Redis can fail between the PING and the queue reads. Report the engine
                // unhealthy instead of letting a status check throw.
                redisReachable = false;
                depths.clear();
            }
        }

        return new EngineStatus(redisReachable, schedulerEnabled, schedulerRunning,
                maintenanceRunning, workerCapacity.inFlight(), workerCapacity.maxInFlight(),
                depths);
    }
}
