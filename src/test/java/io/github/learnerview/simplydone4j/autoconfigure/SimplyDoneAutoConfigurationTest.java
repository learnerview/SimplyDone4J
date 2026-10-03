package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.metrics.QueueDepthSampler;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisUniquenessGuard;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimplyDoneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SimplyDoneMetricsAutoConfiguration.class,
                    SimplyDoneAutoConfiguration.class,
                    SimplyDoneHealthAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void shouldCreateCoreBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SimplyDoneProperties.class);
            assertThat(context).hasSingleBean(HandlerRegistry.class);
            assertThat(context).hasSingleBean(JobRepository.class);
            assertThat(context).hasSingleBean(QueueRepository.class);
            assertThat(context).hasSingleBean(JobSubmissionService.class);
            assertThat(context).hasSingleBean(JobExecutorService.class);
            assertThat(context).hasSingleBean(RateLimiterService.class);
            assertThat(context).hasSingleBean(RetryService.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(MonitoringService.class);
            assertThat(context).hasSingleBean(SchedulerService.class);
            assertThat(context).hasSingleBean(WorkerMaintenanceService.class);
            assertThat(context).hasSingleBean(DeadLetterService.class);
            assertThat(context.getBean(UniquenessGuard.class).isEnabled()).isFalse();
        });
    }

    @Test
    void shouldBackTheUniquenessGuardWithRedisWhenEnabled() {
        contextRunner.withPropertyValues("simplydone4j.uniqueness.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(UniquenessGuard.class);
                    UniquenessGuard guard = context.getBean(UniquenessGuard.class);
                    assertThat(guard).isInstanceOf(RedisUniquenessGuard.class);
                    assertThat(guard.isEnabled()).isTrue();
                });
    }

    @Test
    void shouldFallBackToNoOpMetricsWithoutAMeterRegistry() {
        // Micrometer is an optional dependency, so the engine has to start and run
        // unchanged in an application that never adds a MeterRegistry.
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JobMetrics.class);
            assertThat(context.getBean(JobMetrics.class)).isSameAs(JobMetrics.NOOP);
            // The sampler bean is still created so the scheduled task is visible, but it
            // short-circuits instead of scanning Redis for numbers nothing will read.
            assertThat(context).hasSingleBean(QueueDepthSampler.class);
        });
    }

    @Test
    void shouldWireMicrometerWhenAMeterRegistryIsPresent() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(JobMetrics.class);
                    assertThat(context.getBean(JobMetrics.class).isEnabled()).isTrue();
                    assertThat(context).hasSingleBean(QueueDepthSampler.class);
                });
    }

    @Test
    void shouldUseNoOpMetricsWhenMetricsAreDisabled() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("simplydone4j.metrics.enabled=false")
                .run(context -> assertThat(context.getBean(JobMetrics.class)).isSameAs(JobMetrics.NOOP));
    }

    @Test
    void shouldSkipTheQueueDepthSamplerWhenDisabled() {
        contextRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("simplydone4j.metrics.queue-depth=false")
                .run(context -> assertThat(context).doesNotHaveBean(QueueDepthSampler.class));
    }

    @Test
    void shouldExposeHealthByDefaultAndAllowItToBeDisabled() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(HealthIndicator.class));

        contextRunner
                .withPropertyValues("simplydone4j.health.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }

    @Test
    void shouldBindHealthThresholds() {
        contextRunner
                .withPropertyValues("simplydone4j.health.dead-letter-threshold=5",
                        "simplydone4j.health.max-queue-depth=250")
                .run(context -> {
                    SimplyDoneProperties props = context.getBean(SimplyDoneProperties.class);
                    assertThat(props.getHealth().getDeadLetterThreshold()).isEqualTo(5L);
                    assertThat(props.getHealth().getMaxQueueDepth()).isEqualTo(250L);
                });
    }

    @Test
    void shouldDisableSchedulerWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("simplydone4j.scheduler.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulerService.class);
                    assertThat(context).doesNotHaveBean(WorkerMaintenanceService.class);
                    // Submission and monitoring still work
                    assertThat(context).hasSingleBean(JobSubmissionService.class);
                    assertThat(context).hasSingleBean(MonitoringService.class);
                });
    }

    @Test
    void shouldDisableMonitoringWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("simplydone4j.monitoring.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MonitoringService.class);
                    assertThat(context).hasSingleBean(JobSubmissionService.class);
                });
    }

    @Test
    void shouldAllowBeanOverride() {
        contextRunner
                .withBean("customRepo", JobRepository.class, () -> {
                    return new JobRepository() {
                        @Override public void save(io.github.learnerview.simplydone4j.entity.JobEntity job) {}
                        @Override public java.util.Optional<io.github.learnerview.simplydone4j.entity.JobEntity> findById(String jobId) { return java.util.Optional.empty(); }
                        @Override public java.util.Optional<io.github.learnerview.simplydone4j.entity.JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey) { return java.util.Optional.empty(); }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findReadyToRun(io.github.learnerview.simplydone4j.model.JobStatus status, java.time.Instant before, int limit) { return java.util.List.of(); }
                        @Override public long countByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return 0; }
                        @Override public long countByStatusAndPriority(io.github.learnerview.simplydone4j.model.JobStatus status, io.github.learnerview.simplydone4j.model.JobPriority priority) { return 0; }
                        @Override public int claimForExecution(String jobId, String leaseToken, String workerId, java.time.Instant visibleUntil, java.time.Instant now, io.github.learnerview.simplydone4j.model.JobStatus fromStatus, io.github.learnerview.simplydone4j.model.JobStatus toStatus) { return 0; }
                        @Override public boolean saveIfLeaseHeld(io.github.learnerview.simplydone4j.entity.JobEntity job, String expectedLeaseToken) { return false; }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByProducerAndStatus(String producer, io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                    };
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(JobRepository.class);
                });
    }
}
