package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties.Executor;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.RetryPolicy;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.DeadLetterServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.DefaultJobSignal;
import io.github.learnerview.simplydone4j.service.impl.EngineStatusServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.ExponentialBackoffRetryPolicy;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.LeaseRenewer;
import io.github.learnerview.simplydone4j.service.impl.RedisIdempotencyServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.SchedulerEngine;
import io.github.learnerview.simplydone4j.service.impl.SchedulerLoop;
import io.github.learnerview.simplydone4j.service.impl.WorkerMaintenanceScheduler;
import io.github.learnerview.simplydone4j.service.impl.WorkerMaintenanceServiceImpl;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
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
@ConditionalOnClass({StringRedisTemplate.class})
public final class SimplyDoneAutoConfiguration {

    /**
     * Internal mapper for job hashes and execution logs.
     *
     * <p>This must not be the application's {@code ObjectMapper}. A naming strategy,
     * date format, or custom module registered for API serialization would otherwise
     * change the Redis field names and make existing job records unreadable.</p>
     */
    @Bean(name = "simplyDoneObjectMapper")
    @ConditionalOnMissingBean(name = "simplyDoneObjectMapper")
    public ObjectMapper simplyDoneObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean(name = "jobTaskExecutor")
    @ConditionalOnMissingBean(name = "jobTaskExecutor")
    public ThreadPoolTaskExecutor jobTaskExecutor(SimplyDoneProperties props) {
        Executor exec = props.getExecutor();
        int maxPoolSize = Math.max(1, exec.getMaxPoolSize());
        int corePoolSize = Math.min(maxPoolSize, Math.max(1, exec.getCorePoolSize()));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        // A queued task has already claimed its lease, so parking it in the executor can
        // let that lease expire before the handler starts. Dispatch permits cap in-flight
        // tasks at maxPoolSize, so hand off directly and let the pool grow from core to max.
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(Math.max(0, exec.getKeepAliveSeconds()));
        executor.setThreadNamePrefix("sd4j-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(exec.getAwaitTerminationSeconds());
        RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler(handler);
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public JobSignal jobSignal() {
        return new DefaultJobSignal();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerCapacity workerCapacity(SimplyDoneProperties props) {
        return new WorkerCapacity(Math.max(1, props.getExecutor().getMaxPoolSize()));
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean
    public ScheduledExecutorService jobTimeoutScheduler() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "simplydone4j-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public LeaseRenewer leaseRenewer(JobRepository jobRepo,
                                     ScheduledExecutorService jobTimeoutScheduler,
                                     SimplyDoneProperties props) {
        return new LeaseRenewer(jobRepo, jobTimeoutScheduler, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueueRepository queueRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisQueueRepository(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobRepository jobRepository(StringRedisTemplate redis,
                                        @Qualifier("simplyDoneObjectMapper") ObjectMapper objectMapper,
                                        SimplyDoneProperties props) {
        return new RedisJobRepository(redis, objectMapper, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutionLogRepository jobExecutionLogRepository(
            StringRedisTemplate redis,
            @Qualifier("simplyDoneObjectMapper") ObjectMapper objectMapper,
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
    public JobMapper jobMapper(@Qualifier("simplyDoneObjectMapper") ObjectMapper objectMapper) {
        return new JobMapper(objectMapper);
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
                                                      SimplyDoneProperties props,
                                                      JobMapper jobMapper, JobEventPublisher eventPublisher,
                                                      IdempotencyService idempotencyService,
                                                      JobSignal jobSignal,
                                                      ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(() ->
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        return new JobSubmissionServiceImpl(jobRepo, queueRepo, props, jobMapper, eventPublisher,
                idempotencyService, validator, jobSignal);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutorService jobExecutorService(JobRepository jobRepo, RetryService retryService,
                                                  HandlerRegistry handlerRegistry, JobEventPublisher eventPublisher,
                                                  @Qualifier("jobTaskExecutor") ThreadPoolTaskExecutor executor,
                                                  WorkerCapacity workerCapacity, JobSignal jobSignal,
                                                  LeaseRenewer leaseRenewer,
                                                  ScheduledExecutorService jobTimeoutScheduler,
                                                  SimplyDoneProperties props) {
        return new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                executor, props.getExecutor().getDefaultTimeoutSeconds(), workerCapacity, jobSignal,
                leaseRenewer, jobTimeoutScheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerService schedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                                             JobExecutorService jobExecutor, WorkerCapacity workerCapacity,
                                             JobSignal jobSignal, SimplyDoneProperties props) {
        return new SchedulerEngine(queueRepo, jobRepo, jobExecutor, workerCapacity, jobSignal, props);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerLoop schedulerLoop(SchedulerService scheduler, JobSignal jobSignal,
                                       SimplyDoneProperties props) {
        return new SchedulerLoop(scheduler, jobSignal, props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public WorkerMaintenanceService workerMaintenanceService(JobRepository jobRepo, QueueRepository queueRepo,
                                                              RetryService retryService, JobSignal jobSignal,
                                                              SimplyDoneProperties props) {
        return new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, props, jobSignal);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public WorkerMaintenanceScheduler workerMaintenanceScheduler(
            WorkerMaintenanceService maintenance, SimplyDoneProperties props) {
        return new WorkerMaintenanceScheduler(maintenance, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeadLetterService deadLetterService(JobRepository jobRepo, QueueRepository queueRepo,
                                               JobMapper jobMapper, JobEventPublisher eventPublisher,
                                               JobSignal jobSignal, SimplyDoneProperties props) {
        return new DeadLetterServiceImpl(jobRepo, queueRepo, jobMapper, eventPublisher,
                jobSignal, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public EngineStatusService engineStatusService(StringRedisTemplate redis,
                                                   ObjectProvider<SchedulerLoop> scheduler,
                                                   ObjectProvider<WorkerMaintenanceScheduler> maintenance,
                                                   QueueRepository queueRepo,
                                                   WorkerCapacity workerCapacity,
                                                   SimplyDoneProperties props) {
        return new EngineStatusServiceImpl(redis, scheduler, maintenance, queueRepo,
                workerCapacity, props);
    }
}
