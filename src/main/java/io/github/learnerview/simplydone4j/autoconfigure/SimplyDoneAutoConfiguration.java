package io.github.learnerview.simplydone4j.autoconfigure;

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
import io.github.learnerview.simplydone4j.repository.IdempotencyStore;
import io.github.learnerview.simplydone4j.repository.RedisIdempotencyStore;
import io.github.learnerview.simplydone4j.service.JobExecutorOptions;
import io.github.learnerview.simplydone4j.service.JobExecutorService;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import io.github.learnerview.simplydone4j.service.MonitoringService;
import io.github.learnerview.simplydone4j.api.DefaultJobClient;
import io.github.learnerview.simplydone4j.api.JobClient;
import io.github.learnerview.simplydone4j.api.spi.AdmissionPolicy;
import io.github.learnerview.simplydone4j.api.spi.JacksonPayloadCodec;
import io.github.learnerview.simplydone4j.api.spi.PayloadCodec;
import io.github.learnerview.simplydone4j.service.RetryPolicy;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.SchedulerService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WorkerMaintenanceService;
import io.github.learnerview.simplydone4j.service.impl.DeadLetterServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.DefaultJobSignal;
import io.github.learnerview.simplydone4j.service.impl.ExponentialBackoffRetryPolicy;
import io.github.learnerview.simplydone4j.service.impl.ExecutorCapacity;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.MonitoringServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.SchedulerEngine;
import io.github.learnerview.simplydone4j.service.impl.SchedulerLoop;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
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
@ConditionalOnClass({StringRedisTemplate.class})
public final class SimplyDoneAutoConfiguration {

    /**
     * Supplies a Jackson 3 {@link ObjectMapper} only when the application has none.
     *
     * <p>This configuration runs after Boot's Jackson auto-configuration, so a mapper the
     * host provides still wins. The fallback exists because a starter should not require
     * the host to hand it one: the payload mapper needs a working codec, and an
     * application that does not depend on Boot's JSON starter has no mapper bean at all.</p>
     *
     * <p>Timestamps are written as ISO-8601 strings rather than epoch numbers. That is
     * also Boot's default, and it keeps payloads produced by one deployment readable by
     * another that has different date settings.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(
            org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * The worker pool.
     *
     * <p>Pool size defaults to the size of the machine rather than to a fixed number, so
     * the library neither wastes a small container nor floods a large one. The defaults
     * are derived from the available processors and clamped to a range that keeps a
     * two-core host responsive and an eight-core host busy; anything the user sets
     * explicitly wins.</p>
     *
     * <p>Core and max are the same size on purpose. With a bounded queue a thread pool
     * does not grow past its core size until the queue is full, so a larger max would
     * only ever take effect under exactly the conditions the capacity-aware scheduler is
     * designed to avoid.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolTaskExecutor jobTaskExecutor(SimplyDoneProperties props) {
        Executor exec = props.getExecutor();
        int core = resolvePoolSize(exec.getCorePoolSize());
        int max = resolvePoolSize(exec.getMaxPoolSize());
        if (max < core) max = core;

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("sd4j-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(exec.getAwaitTerminationSeconds());
        // AbortPolicy, deliberately not CallerRunsPolicy.
        //
        // The submit happens on the scheduler thread (SchedulerEngine.poll), so CallerRuns
        // would execute the job handler *inline on the scheduler thread* whenever the pool is
        // saturated. A handler may run for defaultTimeoutSeconds, which parks the scheduler:
        // SchedulerEngine.poll, WorkerMaintenanceServiceImpl.promoteRetries and
        // recoverExpiredLeases all share Spring's single scheduled-task thread, so a busy
        // pool silently stops lease reaping and retry promotion too. Expired leases then sit
        // unrecovered and retried jobs stall until the backlog clears.
        //
        // Aborting is safe because JobExecutorServiceImpl returns a rejected job to QUEUED
        // and re-arms its queue entry, while SchedulerEngine restores anything that throws
        // during dispatch. So a rejection means "not now" rather than "lost".
        RejectedExecutionHandler handler = new ThreadPoolExecutor.AbortPolicy();
        executor.setRejectedExecutionHandler(handler);
        executor.initialize();
        return executor;
    }

    /**
     * Resolves a configured pool size: an explicit value wins, zero means "decide from
     * the machine".
     */
    private static int resolvePoolSize(int configured) {
        if (configured > 0) return configured;
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
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
    public JobRepository jobRepository(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisJobRepository(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobExecutionLogRepository jobExecutionLogRepository(StringRedisTemplate redis,
                                                                SimplyDoneProperties props) {
        return new RedisJobExecutionLogRepository(redis, props);
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

    /**
     * The default policy admits everything. An application that wants a rate limit, a
     * quota or a per-tenant tier declares its own {@link AdmissionPolicy} bean and this
     * one steps aside.
     */
    @Bean
    @ConditionalOnMissingBean
    public AdmissionPolicy admissionPolicy() {
        return AdmissionPolicy.allowAll();
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
    public IdempotencyStore idempotencyStore(StringRedisTemplate redis, SimplyDoneProperties props) {
        return new RedisIdempotencyStore(redis, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobSubmissionService jobSubmissionService(JobRepository jobRepo, QueueRepository queueRepo,
                                                      AdmissionPolicy admissionPolicy, SimplyDoneProperties props,
                                                      JobMapper jobMapper, JobEventPublisher eventPublisher,
                                                      IdempotencyStore idempotencyStore,
                                                      JobMetrics metrics,
                                                      ObjectProvider<Validator> validatorProvider) {
        Validator validator = validatorProvider.getIfAvailable(() ->
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        return new JobSubmissionServiceImpl(jobRepo, queueRepo, admissionPolicy, props, jobMapper, eventPublisher,
                idempotencyStore, validator, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadCodec payloadCodec(ObjectMapper objectMapper) {
        return new JacksonPayloadCodec(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JobClient jobClient(JobSubmissionService submissionService,
                               JobRepository jobRepo,
                               QueueRepository queueRepo,
                               DeadLetterService deadLetterService,
                               JobMapper jobMapper,
                               PayloadCodec payloadCodec,
                               ObjectProvider<JobEventPublisher> eventPublisherProvider) {
        DefaultJobClient client = new DefaultJobClient(submissionService, jobRepo, queueRepo,
                deadLetterService, jobMapper, payloadCodec, eventPublisherProvider.getIfAvailable());
        return client;
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
                                                  UniquenessGuard uniquenessGuard,
                                                  ThreadPoolTaskExecutor executor,
                                                  SimplyDoneTaskScheduler taskScheduler,
                                                  JobMetrics metrics,
                                                  SimplyDoneProperties props) {
        JobExecutorOptions options = JobExecutorOptions.builder()
                .uniquenessGuard(uniquenessGuard)
                .overlapDeferSeconds(props.getUniqueness().getDeferSeconds())
                .uniquenessLockTtlSeconds(props.getUniqueness().getTtlSeconds())
                .defaultTimeoutSeconds(props.getExecutor().getDefaultTimeoutSeconds())
                .leaseTimeoutSeconds(props.getWorker().getLeaseTimeoutSeconds())
                .metrics(metrics)
                .timer(taskScheduler.executor())
                .build();
        return new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher,
                executor, options);
    }

    /**
     * The library's own timer.
     *
     * <p>Not published as a {@code ScheduledExecutorService} or a {@code TaskScheduler}
     * bean: Spring Boot's {@code TaskSchedulingAutoConfiguration} backs off completely when
     * the context contains one of those, so a starter that publishes one silently takes
     * the host application's own {@code @Scheduled} methods with it. The type here is
     * deliberately private to the library, and the periodic work below is scheduled
     * explicitly rather than through {@code @EnableScheduling}.</p>
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public SimplyDoneTaskScheduler simplyDoneTaskScheduler(SimplyDoneProperties props) {
        return SimplyDoneTaskScheduler.create("sd4j-timer", props.getScheduler().getTimerPoolSize());
    }

    /**
     * The wake-up signal the scheduler loop listens on.
     */
    @Bean
    @ConditionalOnMissingBean
    public JobSignal jobSignal() {
        return new DefaultJobSignal();
    }

    /**
     * The scheduler loop.
     *
     * <p>Polls on its own thread and waits for a signal in between, so a job that was
     * just submitted is picked up at once instead of after the rest of the poll
     * interval. The timeout remains in place for the work that becomes runnable because
     * time passed rather than because somebody said so: a delayed job reaching its due
     * date, a lease expiring.</p>
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerLoop schedulerLoop(SchedulerService schedulerService, JobSignal signal) {
        SchedulerLoop loop = new SchedulerLoop(schedulerService, signal);
        loop.start();
        return loop;
    }

    /**
     * Schedules the library's periodic work on the library's own timer.
     *
     * <p>Every task is scheduled with a fixed delay and swallows its own failures: a task
     * that raises stops being repeated, and one dead reaper is enough to stall recovery.
     * Polling is not here: the scheduler loop does that, and does it as soon as it hears
     * that work became runnable.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public SimplyDoneScheduledWork simplyDoneScheduledWork(SimplyDoneTaskScheduler taskScheduler,
                                                           ObjectProvider<WorkerMaintenanceService> workerMaintenance,
                                                           SimplyDoneProperties props) {
        SimplyDoneScheduledWork work = new SimplyDoneScheduledWork(taskScheduler);

        workerMaintenance.ifAvailable(service -> {
            work.schedule("sd4j-retry-promoter", service::promoteRetries,
                    Duration.ofMillis(props.getWorker().getRetryPromoterIntervalMs()),
                    Duration.ofMillis(props.getWorker().getRetryPromoterIntervalMs()));
            work.schedule("sd4j-lease-reaper", service::recoverExpiredLeases,
                    Duration.ofMillis(props.getWorker().getLeaseReaperIntervalMs()),
                    Duration.ofMillis(props.getWorker().getLeaseReaperIntervalMs()));
            work.schedule("sd4j-queue-reconciler", service::reconcileQueuedJobs,
                    Duration.ofMillis(props.getWorker().getLeaseReaperIntervalMs()),
                    Duration.ofMillis(props.getWorker().getLeaseReaperIntervalMs()));
            work.schedule("sd4j-index-sweeper", service::pruneTerminalIndexes,
                    Duration.ofMillis(props.getRetention().getSweepIntervalMs()),
                    Duration.ofMillis(props.getRetention().getSweepIntervalMs()));
        });

        return work;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public SchedulerService schedulerEngine(QueueRepository queueRepo, JobRepository jobRepo,
                                             JobExecutorService jobExecutor,
                                             FencingTokenSequence fencingTokens,
                                             JobMetrics metrics,
                                             ThreadPoolTaskExecutor executor,
                                             SimplyDoneProperties props) {
        return new SchedulerEngine(queueRepo, jobRepo, jobExecutor, fencingTokens, props, metrics,
                new ExecutorCapacity(executor));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.monitoring", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MonitoringService monitoringService(JobQueryRepository jobRepo, QueueRepository queueRepo) {
        return new MonitoringServiceImpl(jobRepo, queueRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.scheduler", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public WorkerMaintenanceService workerMaintenanceService(JobRepository jobRepo, QueueRepository queueRepo,
                                                              RetryService retryService,
                                                              JobMetrics metrics,
                                                              JobEventPublisher eventPublisher,
                                                              SimplyDoneProperties props) {
        return new WorkerMaintenanceServiceImpl(jobRepo, queueRepo, retryService, metrics, eventPublisher);
    }
}
