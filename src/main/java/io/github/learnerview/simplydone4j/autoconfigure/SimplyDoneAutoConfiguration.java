package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties.Executor;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.repository.FencingTokenSequence;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisFencingTokenSequence;
import io.github.learnerview.simplydone4j.repository.RedisJobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisUniquenessGuard;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.service.RateLimiterService;
import io.github.learnerview.simplydone4j.service.RetryPolicy;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WebhookService;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.DeadLetterServiceImpl;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
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

    /**
     * Supplies a Jackson 2 {@link ObjectMapper} only when the application has none.
     *
     * <p>This configuration runs after Boot's Jackson auto-configuration, so a mapper the
     * host provides still wins. The fallback exists because Spring Boot 4 defaults to
     * Jackson 3 ({@code tools.jackson}) and its auto-configuration therefore no longer
     * contributes a {@code com.fasterxml} mapper, while job payloads carry
     * {@code java.time} values that need {@code JavaTimeModule}. Without this bean any
     * Boot 4 application failed at startup with "No qualifying bean of type
     * ObjectMapper". A starter should not require the host to hand it one.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

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
        // AbortPolicy, deliberately not CallerRunsPolicy.
        //
        // The submit happens on the @Scheduled thread (SchedulerEngine.poll), so CallerRuns
        // would execute the job handler *inline on the scheduler thread* whenever the pool is
        // saturated. A handler may run for defaultTimeoutSeconds, which parks the scheduler:
        // SchedulerEngine.poll, WorkerMaintenanceServiceImpl.promoteRetries and
        // recoverExpiredLeases all share Spring's single scheduled-task thread, so a busy
        // pool silently stops lease reaping and retry promotion too. Expired leases then sit
        // unrecovered and retried jobs stall until the backlog clears.
        //
        // Aborting is safe because the job was already removed from the priority ZSET by
        // claimReady, and SchedulerEngine restores anything that fails to dispatch. So a
        // rejection means "not now, still on the queue" rather than "lost".
        RejectedExecutionHandler handler = new ThreadPoolExecutor.AbortPolicy();
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
    public FencingTokenSequence fencingTokenSequence(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisFencingTokenSequence(redis, props);
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

    /**
     * Always defined so the other engine beans can depend on the type unconditionally.
     *
     * <p>Deliberately free of any Micrometer reference. When Micrometer is on the
     * classpath, {@link SimplyDoneMetricsAutoConfiguration} contributes the real
     * instrumented bean first and this one backs off via
     * {@code @ConditionalOnMissingBean}; when it is absent, this no-op keeps the
     * engine working. Declaring a {@code ObjectProvider<MeterRegistry>} parameter
     * here instead would make Spring resolve that optional type while building the
     * bean definition and fail with {@code TypeNotPresentException} on hosts that
     * omit the optional dependency.
     */
    @Bean
    @ConditionalOnMissingBean
    public JobMetrics jobMetrics() {
        return JobMetrics.NOOP;
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
                                                      JobMetrics metrics,
                                                      ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(() ->
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        return new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter, props, jobMapper, eventPublisher,
                idempotencyService, validator, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebhookService webhookService(SimplyDoneProperties props) {
        return new HttpWebhookServiceImpl(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterService deadLetterService(JobRepository jobRepo, QueueRepository queueRepo,
                                               JobEventPublisher eventPublisher, JobMetrics metrics,
                                               SimplyDoneProperties props) {
        return new DeadLetterServiceImpl(jobRepo, queueRepo, eventPublisher, props, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public UniquenessGuard uniquenessGuard(StringRedisTemplate redis, SimplyDoneProperties props) {
        return props.getUniqueness().isEnabled()
                ? new RedisUniquenessGuard(redis, props)
                : UniquenessGuard.DISABLED;
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutorService jobExecutorService(JobRepository jobRepo, QueueRepository queueRepo,
                                                  RetryService retryService,
                                                  HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                                  WebhookService webhookService, UniquenessGuard uniquenessGuard,
                                                  ThreadPoolTaskExecutor executor,
                                                  ScheduledExecutorService jobTimeoutScheduler,
                                                  JobMetrics metrics,
                                                  SimplyDoneProperties props) {
        return new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher,
                webhookService, uniquenessGuard, props.getUniqueness().getDeferSeconds(),
                props.getUniqueness().getTtlSeconds(), executor,
                jobTimeoutScheduler, props.getExecutor().getDefaultTimeoutSeconds(), metrics);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public ScheduledExecutorService jobTimeoutScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sd4j-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerService schedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                                             JobExecutorService jobExecutor,
                                             FencingTokenSequence fencingTokens,
                                             JobMetrics metrics,
                                             SimplyDoneProperties props) {
        return new SchedulerEngine(queueRepo, jobRepo, jobExecutor, fencingTokens, props, metrics);
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
                                                              JobMetrics metrics,
                                                              SimplyDoneProperties props) {
        return new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, props, metrics);
    }
}
