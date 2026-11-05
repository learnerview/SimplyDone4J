package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.WorkerMaintenanceScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimplyDoneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SimplyDoneAutoConfiguration.class))
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
            assertThat(context).hasSingleBean(RetryService.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskExecutor.class);
            assertThat(context).hasSingleBean(SchedulerService.class);
            assertThat(context).hasSingleBean(WorkerMaintenanceService.class);
            assertThat(context).hasSingleBean(WorkerMaintenanceScheduler.class);
            assertThat(context).hasSingleBean(DeadLetterService.class);
            assertThat(context).hasSingleBean(EngineStatusService.class);
        });
    }

    @Test
    void shouldKeepPersistenceMapperSeparateFromTheApplicationMapper() {
        ObjectMapper applicationMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        contextRunner
                .withBean("applicationObjectMapper", ObjectMapper.class, () -> applicationMapper)
                .run(context -> {
                    assertThat(context).hasBean("simplyDoneObjectMapper");
                    ObjectMapper internal = context.getBean("simplyDoneObjectMapper", ObjectMapper.class);
                    assertThat(internal).isNotSameAs(applicationMapper);
                    assertThat(internal.getPropertyNamingStrategy())
                            .isNotEqualTo(PropertyNamingStrategies.SNAKE_CASE);
                    assertThat(context).hasSingleBean(JobSubmissionService.class);
                });
    }

    @Test
    void shouldCreateJobExecutorWhenAnApplicationExecutorAlreadyExists() {
        contextRunner
                .withBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class, () -> {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    executor.setThreadNamePrefix("application-");
                    executor.initialize();
                    return executor;
                })
                .run(context -> {
                    assertThat(context).hasBean("applicationTaskExecutor");
                    assertThat(context).hasBean("jobTaskExecutor");
                    assertThat(context).hasSingleBean(JobExecutorService.class);
                    assertThat(context.getBean("jobTaskExecutor"))
                            .isNotSameAs(context.getBean("applicationTaskExecutor"));
                });
    }

    @Test
    void shouldDisableSchedulerWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("simplydone4j.scheduler.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulerService.class);
                    assertThat(context).doesNotHaveBean(WorkerMaintenanceService.class);
                    assertThat(context).doesNotHaveBean(WorkerMaintenanceScheduler.class);
                    // Submission still works
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
                        @Override public boolean extendLease(String jobId, String expectedLeaseToken, java.time.Instant visibleUntil) { return false; }
                        @Override public void delete(String jobId) {}
                        @Override public boolean transitionStatusIfCurrent(String jobId, io.github.learnerview.simplydone4j.model.JobStatus expected, io.github.learnerview.simplydone4j.model.JobStatus target) { return false; }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByProducerAndStatus(String producer, io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                    };
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(JobRepository.class);
                });
    }
}
