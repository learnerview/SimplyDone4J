package io.github.learnerview.simplydone4j.autoconfigure;

import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SimplyDoneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SimplyDoneAutoConfiguration.class));

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
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findExpiredLeases(io.github.learnerview.simplydone4j.model.JobStatus status, java.time.Instant before, int limit) { return java.util.List.of(); }
                        @Override public long countByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return 0; }
                        @Override public long countByStatusAndPriority(io.github.learnerview.simplydone4j.model.JobStatus status, io.github.learnerview.simplydone4j.model.JobPriority priority) { return 0; }
                        @Override public int claimForExecution(String jobId, String leaseToken, String workerId, java.time.Instant visibleUntil, java.time.Instant now, io.github.learnerview.simplydone4j.model.JobStatus fromStatus, io.github.learnerview.simplydone4j.model.JobStatus toStatus) { return 0; }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByProducerAndStatus(String producer, io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                        @Override public java.util.List<io.github.learnerview.simplydone4j.entity.JobEntity> findByStatus(io.github.learnerview.simplydone4j.model.JobStatus status) { return java.util.List.of(); }
                    };
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(JobRepository.class);
                });
    }
}
