package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EngineStatusServiceImplTest {

    @Mock StringRedisTemplate redis;
    @Mock ObjectProvider<SchedulerLoop> scheduler;
    @Mock ObjectProvider<WorkerMaintenanceScheduler> maintenance;
    @Mock QueueRepository queueRepository;
    @Mock SchedulerLoop schedulerLoop;
    @Mock WorkerMaintenanceScheduler maintenanceScheduler;

    private SimplyDoneProperties properties;
    private WorkerCapacity capacity;
    private EngineStatusServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new SimplyDoneProperties();
        capacity = new WorkerCapacity(5);
        capacity.tryAcquire();
        service = new EngineStatusServiceImpl(redis, scheduler, maintenance, queueRepository,
                capacity, properties);
    }

    @Test
    void reportsAHealthyEngineWhenRedisAndBothLoopsAreUp() {
        stubRedisPong();
        stubLoops(true, true);
        when(queueRepository.queueSize(JobPriority.HIGH)).thenReturn(2L);
        when(queueRepository.queueSize(JobPriority.NORMAL)).thenReturn(3L);
        when(queueRepository.queueSize(JobPriority.LOW)).thenReturn(4L);

        EngineStatus status = service.status();

        assertTrue(status.redisReachable());
        assertTrue(status.schedulerRunning());
        assertTrue(status.maintenanceRunning());
        assertTrue(status.healthy());
        assertEquals(1, status.workersInFlight());
        assertEquals(5, status.workerCapacity());
        assertEquals(2L, status.queueDepths().get(JobPriority.HIGH));
        assertEquals(3L, status.queueDepths().get(JobPriority.NORMAL));
        assertEquals(4L, status.queueDepths().get(JobPriority.LOW));
    }

    @Test
    void reportsUnhealthyWhenRedisIsUnreachable() {
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new RuntimeException("connection refused"));
        stubLoops(true, true);

        EngineStatus status = service.status();

        assertFalse(status.redisReachable());
        assertFalse(status.healthy());
    }

    @Test
    void doesNotThrowWhenRedisFailsAfterThePing() {
        stubRedisPong();
        stubLoops(true, true);
        when(queueRepository.queueSize(JobPriority.HIGH))
                .thenThrow(new RuntimeException("connection lost"));

        EngineStatus status = service.status();

        assertFalse(status.redisReachable());
        assertFalse(status.healthy());
        assertTrue(status.queueDepths().isEmpty());
    }

    @Test
    void reportsUnhealthyWhenTheSchedulerLoopStopped() {
        stubRedisPong();
        stubLoops(false, true);

        EngineStatus status = service.status();

        assertFalse(status.schedulerRunning());
        assertFalse(status.healthy());
        assertFalse(service.isHealthy());
    }

    @Test
    void ignoresLoopStateWhenTheSchedulerIsDisabled() {
        properties.getScheduler().setEnabled(false);
        stubRedisPong();

        EngineStatus status = service.status();

        assertFalse(status.schedulerEnabled());
        assertFalse(status.schedulerRunning());
        assertTrue(status.healthy());
    }

    @SuppressWarnings("unchecked")
    private void stubRedisPong() {
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");
    }

    private void stubLoops(boolean schedulerRunning, boolean maintenanceRunning) {
        when(scheduler.getIfAvailable()).thenReturn(schedulerLoop);
        when(maintenance.getIfAvailable()).thenReturn(maintenanceScheduler);
        when(schedulerLoop.isRunning()).thenReturn(schedulerRunning);
        when(maintenanceScheduler.isRunning()).thenReturn(maintenanceRunning);
    }
}
