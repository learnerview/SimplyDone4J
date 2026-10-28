package io.github.learnerview.simplydone4j.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.api.spi.AdmissionDecision;
import io.github.learnerview.simplydone4j.api.spi.AdmissionPolicy;
import io.github.learnerview.simplydone4j.api.spi.AdmissionRequest;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.metrics.QueueDepthSampler;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisUniquenessGuard;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.api.spi.AdmissionPolicy;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.SchedulerLoop;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

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
            assertThat(context).hasSingleBean(io.github.learnerview.simplydone4j.api.JobClient.class);
            assertThat(context).hasSingleBean(io.github.learnerview.simplydone4j.api.spi.PayloadCodec.class);
            assertThat(context).hasSingleBean(JobExecutorService.class);
            assertThat(context).hasSingleBean(AdmissionPolicy.class);
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
    void shouldDefaultToAnAdmissionPolicyThatAdmitsEverything() {
        contextRunner.run(context -> {
            AdmissionPolicy policy = context.getBean(AdmissionPolicy.class);
            AdmissionDecision decision = policy.decide(new AdmissionRequest(
                    "producer", "jobType", JobPriority.NORMAL, Map.of(), Instant.now()));
            assertThat(decision.admitted()).isTrue();
        });
    }

    @Test
    void shouldLetTheApplicationSupplyItsOwnAdmissionPolicy() {
        AdmissionPolicy rejecting = request -> AdmissionDecision.reject(Duration.ofSeconds(5), "quota exceeded");
        contextRunner
                .withBean(AdmissionPolicy.class, () -> rejecting)
                .run(context -> {
                    assertThat(context).hasSingleBean(AdmissionPolicy.class);
                    assertThat(context.getBean(AdmissionPolicy.class)).isSameAs(rejecting);
                });
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
                    assertThat(context).doesNotHaveBean(SchedulerLoop.class);
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
    void shouldNotTakeOverTheHostsScheduling() {
        contextRunner.run(context -> {
            // Spring Boot's TaskSchedulingAutoConfiguration backs off completely when the
            // context holds a ScheduledExecutorService or a TaskScheduler bean, so a
            // starter that publishes one silently disables the host's own @Scheduled
            // methods. The library keeps its timer to itself.
            assertThat(context).doesNotHaveBean(ScheduledExecutorService.class);
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).hasSingleBean(SimplyDoneTaskScheduler.class);
        });
    }

    /**
     * The scheduler listens for work instead of only looking at the queues on a timer,
     * so a submitted job is picked up at once rather than after the poll interval.
     */
    @Test
    void shouldRunTheSchedulerLoop() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JobSignal.class);
            assertThat(context).hasSingleBean(SchedulerLoop.class);
        });
    }

    /**
     * The worker pool is sized from the machine by default: a fixed number would either
     * waste a small container or flood a large one.
     */
    @Test
    void shouldSizeTheWorkerPoolFromTheMachineByDefault() {
        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
            int processors = Runtime.getRuntime().availableProcessors();

            assertThat(executor.getCorePoolSize())
                    .isEqualTo(Math.max(2, Math.min(8, processors)));
            assertThat(executor.getMaxPoolSize()).isEqualTo(executor.getCorePoolSize());
        });
    }

    @Test
    void shouldLetTheUserPinTheWorkerPool() {
        contextRunner
                .withPropertyValues("simplydone4j.executor.core-pool-size=3",
                        "simplydone4j.executor.max-pool-size=3")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(3);
                });
    }

    @Test
    void shouldAllowBeanOverride() {
        contextRunner
                .withBean("customRepo", JobRepository.class, () -> {
                    return new JobRepository() {
                        @Override public void save(io.github.learnerview.simplydone4j.entity.JobEntity job) {}
                        @Override public java.util.Optional<io.github.learnerview.simplydone4j.entity.JobEntity> findById(String jobId) { return java.util.Optional.empty(); }
                        @Override public void delete(String jobId) {}
                        @Override public boolean transitionStatusIfCurrent(String jobId, io.github.learnerview.simplydone4j.model.JobStatus expected, io.github.learnerview.simplydone4j.model.JobStatus target) { return false; }
                        @Override public long pruneTerminalIndexes() { return 0; }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findReadyToRun(io.github.learnerview.simplydone4j.model.JobStatus status, java.time.Instant before, int limit, int offset) { return java.util.List.of(); }
                        @Override public long countByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return 0; }
                        @Override public long countByStatusAndPriority(io.github.learnerview.simplydone4j.model.JobStatus status, io.github.learnerview.simplydone4j.model.JobPriority priority) { return 0; }
                        @Override public int claimForExecution(String jobId, String leaseToken, String workerId, java.time.Instant visibleUntil, java.time.Instant now, io.github.learnerview.simplydone4j.model.JobStatus fromStatus, io.github.learnerview.simplydone4j.model.JobStatus toStatus) { return 0; }
                        @Override public boolean saveIfLeaseHeld(io.github.learnerview.simplydone4j.entity.JobEntity job, String expectedLeaseToken) { return false; }
                        @Override public boolean extendLease(String jobId, String expectedLeaseToken, java.time.Instant newVisibleUntil) { return false; }
                        @Override public boolean updateProgress(String jobId, double percent, String message) { return false; }
                        @Override public io.github.learnerview.simplydone4j.model.CancelOutcome requestCancellation(String jobId, String cancelledBy) { return io.github.learnerview.simplydone4j.model.CancelOutcome.NOT_CANCELLABLE; }
                        @Override public boolean isCancellationRequested(String jobId) { return false; }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByProducerAndStatus(String producer, io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                    };
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(JobRepository.class);
                });
    }
}
