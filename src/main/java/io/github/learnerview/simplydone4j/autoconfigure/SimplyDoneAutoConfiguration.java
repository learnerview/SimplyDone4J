package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties.Executor;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RetryPolicy;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.WebhookService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.ExponentialBackoffRetryPolicy;
import io.github.learnerview.simplydone4j.service.impl.HttpWebhookServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.InMemoryRateLimiterStrategy;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.MonitoringServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RateLimiterServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RedisIdempotencyServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RedisRateLimiterStrategy;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.SchedulerEngine;
import io.github.learnerview.simplydone4j.service.impl.WorkerMaintenanceServiceImpl;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring Boot auto-configuration for SimplyDone4J.
 *
 * <p>All beans are guarded with {@code @ConditionalOnMissingBean} so application
 * developers can override any component simply by declaring their own bean of the
 * same type.</p>
 *
 * <p>This configuration runs after JacksonAutoConfiguration and
 * RedisAutoConfiguration to ensure those foundational beans are available
 * for injection.</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
})
@EnableConfigurationProperties(SimplyDoneProperties.class)
@EnableScheduling
@ConditionalOnClass({StringRedisTemplate.class})
public final class SimplyDoneAutoConfiguration {

    // ObjectMapper is intentionally NOT declared here.
    // Spring Boot's JacksonAutoConfiguration provides a correctly configured one,
    // and this configuration runs after it (see @AutoConfiguration(after=...)).

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolTaskExecutor jobTaskExecutor(SimplyDoneProperties props) {
        Executor exec = props.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("sd4j-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(exec.getAwaitTerminationSeconds());
        RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler(handler);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueRepository queueRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisQueueRepository(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobRepository jobRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                                        SimplyDoneProperties props) {
        return new RedisJobRepository(redis, objectMapper, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutionLogRepository jobExecutionLogRepository(StringRedisTemplate redis,
                                                                ObjectMapper objectMapper,
                                                                SimplyDoneProperties props) {
        return new RedisJobExecutionLogRepository(redis, objectMapper, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry() {
        return new HandlerRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public JobEventPublisher jobEventPublisher(ApplicationEventPublisher eventPublisher) {
        return new JobEventPublisher(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobMapper jobMapper(ObjectMapper objectMapper) {
        return new JobMapper(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RateLimiterService rateLimiterService(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RateLimiterServiceImpl(
                new RedisRateLimiterStrategy(redis, props),
                new InMemoryRateLimiterStrategy(props)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryPolicy retryPolicy(SimplyDoneProperties props) {
        return new ExponentialBackoffRetryPolicy(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryService retryService(JobRepository jobRepo, JobExecutionLogRepository logRepo,
                                      SimplyDoneProperties props, JobEventPublisher eventPublisher,
                                      RetryPolicy retryPolicy) {
        return new RetryServiceImpl(jobRepo, logRepo, props, eventPublisher, retryPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyService idempotencyService(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisIdempotencyServiceImpl(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobSubmissionService jobSubmissionService(JobRepository jobRepo, QueueRepository queueRepo,
                                                      RateLimiterService rateLimiter, SimplyDoneProperties props,
                                                      JobMapper jobMapper, JobEventPublisher eventPublisher,
                                                      IdempotencyService idempotencyService,
                                                      ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(() ->
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        return new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter, props, jobMapper, eventPublisher,
                idempotencyService, validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookService webhookService() {
        return new HttpWebhookServiceImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutorService jobExecutorService(JobRepository jobRepo, RetryService retryService,
                                                  HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                                  WebhookService webhookService, ThreadPoolTaskExecutor executor,
                                                  SimplyDoneProperties props) {
        return new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher, webhookService,
                executor, props.getExecutor().getDefaultTimeoutSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerService schedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                                             JobExecutorService jobExecutor, SimplyDoneProperties props) {
        return new SchedulerEngine(queueRepo, jobRepo, jobExecutor, props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.monitoring", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MonitoringService monitoringService(JobQueryRepository jobRepo, QueueRepository queueRepo,
                                               JobExecutionLogRepository logRepo) {
        return new MonitoringServiceImpl(jobRepo, queueRepo, logRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public WorkerMaintenanceService workerMaintenanceService(JobRepository jobRepo, QueueRepository queueRepo,
                                                              RetryService retryService,
                                                              SimplyDoneProperties props) {
        return new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, props);
    }
}
