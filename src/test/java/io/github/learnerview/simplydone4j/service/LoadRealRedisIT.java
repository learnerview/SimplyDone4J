package io.github.learnerview.simplydone4j.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.RedisJobRepository;
import io.github.learnerview.simplydone4j.repository.RedisQueueRepository;
import io.github.learnerview.simplydone4j.service.impl.DefaultJobSignal;
import io.github.learnerview.simplydone4j.service.impl.ExponentialBackoffRetryPolicy;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.LeaseRenewer;
import io.github.learnerview.simplydone4j.service.impl.RedisIdempotencyServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.SchedulerEngine;
import io.github.learnerview.simplydone4j.service.impl.SchedulerLoop;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end burst test against a real Redis with two independent scheduler/executor
 * instances sharing one queue.
 *
 * <p>Run explicitly with {@code mvn -Dtest=LoadRealRedisIT test}. The class name does not
 * match Surefire's default include pattern so the ordinary unit/verify run stays fast.
 */
@Tag("load")
class LoadRealRedisIT {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));
    private static final int JOBS = Integer.getInteger("load.jobs", 10_000);
    private static final int COMPLETION_TIMEOUT_SECONDS =
            Integer.getInteger("load.completion-timeout-seconds", 600);
    private static final int POOL_MAX_TOTAL = Integer.getInteger("load.pool.max-total", 64);
    private static final int POOL_MAX_IDLE = Integer.getInteger("load.pool.max-idle", 16);
    private static final int POOL_MIN_IDLE = Integer.getInteger("load.pool.min-idle", 4);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        boolean reachable;
        try {
            GenericObjectPoolConfig<io.lettuce.core.api.StatefulConnection<?, ?>> pool =
                    new GenericObjectPoolConfig<>();
            pool.setMaxTotal(POOL_MAX_TOTAL);
            pool.setMaxIdle(POOL_MAX_IDLE);
            pool.setMinIdle(POOL_MIN_IDLE);
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(HOST, PORT),
                    LettucePoolingClientConfiguration.builder().poolConfig(pool).build());
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            connectionFactory = factory;
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "Needs Redis at " + HOST + ":" + PORT);
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void twoInstancesMustProcessEveryJobExactlyOnce() throws Exception {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getExecutor().setCorePoolSize(16);
        props.getExecutor().setMaxPoolSize(32);
        props.getExecutor().setQueueCapacity(256);
        props.getExecutor().setDefaultTimeoutSeconds(60);
        props.getExecutor().setAwaitTerminationSeconds(10);
        props.getWorker().setLeaseTimeoutSeconds(30);
        props.getScheduler().setPollingIntervalMs(25);
        props.getScheduler().setBusyPollingIntervalMs(2);
        props.getQueue().setMaxDepth(JOBS + 1_000L);
        props.getRetry().setMaxAttempts(1);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JobRepository jobRepo = new RedisJobRepository(redis, mapper, props);
        QueueRepository queueRepo = new RedisQueueRepository(redis, props);
        JobExecutionLogRepository logRepo = new RedisJobExecutionLogRepository(redis, mapper, props);
        JobEventPublisher events = new JobEventPublisher(event -> { });
        RetryService retryService = new RetryServiceImpl(jobRepo, logRepo, props, events,
                new ExponentialBackoffRetryPolicy(props));
        JobMapper jobMapper = new JobMapper(mapper);
        IdempotencyService idempotency = new RedisIdempotencyServiceImpl(redis, props);
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        JobSignal signal = new DefaultJobSignal();
        JobSubmissionService submissions = new JobSubmissionServiceImpl(jobRepo, queueRepo, props,
                jobMapper, events, idempotency, validator, signal);

        HandlerRegistry handlers = new HandlerRegistry();
        Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
        handlers.register("load", context -> {
            executions.computeIfAbsent(context.getJobId(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return "ok";
        });

        List<Instance> instances = List.of(startInstance(props, mapper, jobRepo, retryService,
                events, handlers, signal), startInstance(props, mapper, jobRepo, retryService,
                events, handlers, signal));

        try {
            long submitStart = System.nanoTime();
            ExecutorService submitters = Executors.newFixedThreadPool(24);
            List<Future<JobSubmissionResponse>> futures = new ArrayList<>(JOBS);
            for (int i = 0; i < JOBS; i++) {
                final int index = i;
                futures.add(submitters.submit(() -> {
                    JobSubmissionRequest request = new JobSubmissionRequest();
                    request.setJobType("load");
                    request.setIdempotencyKey("load-key-" + index);
                    request.setPriority(index % 10 == 0 ? "HIGH" : "NORMAL");
                    request.setMaxAttempts(1);
                    return submissions.submit("load-producer", request);
                }));
            }
            for (Future<JobSubmissionResponse> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            submitters.shutdown();
            long submitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitStart);

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(COMPLETION_TIMEOUT_SECONDS);
            long succeeded = 0;
            while (System.nanoTime() < deadline) {
                succeeded = jobRepo.countByStatus(JobStatus.SUCCESS);
                if (succeeded == JOBS) break;
                Thread.sleep(100);
            }
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitStart);

            assertEquals(JOBS, succeeded, "every submitted job must reach SUCCESS");
            assertEquals(JOBS, executions.size(), "every job must execute");
            assertEquals(0, instances.stream().mapToLong(i -> i.executor.getActiveCount()).sum(),
                    "no handler may still be running");
            for (Instance instance : instances) {
                assertEquals(0, instance.executor.getThreadPoolExecutor().getQueue().size(),
                        "no dispatched work may be left in the executor queue");
            }
            for (Map.Entry<String, AtomicInteger> entry : executions.entrySet()) {
                assertEquals(1, entry.getValue().get(),
                        "job " + entry.getKey() + " executed more than once");
            }
            for (JobPriority priority : JobPriority.values()) {
                assertEquals(0, queueRepo.queueSize(priority), "queue must drain");
            }
            assertEquals(0, jobRepo.countByStatus(JobStatus.RUNNING));
            assertEquals(0, jobRepo.countByStatus(JobStatus.DLQ));
            assertEquals(0, jobRepo.countByStatus(JobStatus.RETRY_SCHEDULED));

            System.out.printf("LOAD OK jobs=%d submit=%dms total=%dms submitRate=%.0f/s endToEnd=%.0f/s%n",
                    JOBS, submitMs, totalMs, JOBS * 1000.0 / Math.max(1, submitMs),
                    JOBS * 1000.0 / Math.max(1, totalMs));
            assertTrue(submitMs > 0 && totalMs > 0);
        } finally {
            for (Instance instance : instances) instance.close();
        }
    }

    private Instance startInstance(SimplyDoneProperties props, ObjectMapper mapper,
                                   JobRepository jobRepo, RetryService retryService,
                                   JobEventPublisher events, HandlerRegistry handlers,
                                   JobSignal signal) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(props.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(props.getExecutor().getQueueCapacity());
        executor.setThreadNamePrefix("load-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(props.getExecutor().getAwaitTerminationSeconds());
        executor.initialize();

        ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
        WorkerCapacity capacity = new WorkerCapacity(props.getExecutor().getMaxPoolSize());
        LeaseRenewer renewer = new LeaseRenewer(jobRepo, timeouts, props);
        JobExecutorService jobExecutor = new JobExecutorServiceImpl(jobRepo, retryService,
                handlers, events, executor, props.getExecutor().getDefaultTimeoutSeconds(),
                capacity, signal, renewer, timeouts);
        SchedulerService engine = new SchedulerEngine(
                new RedisQueueRepository(redis, props), jobRepo, jobExecutor, capacity,
                signal, props);
        SchedulerLoop loop = new SchedulerLoop(engine, signal, props);
        loop.start();
        return new Instance(loop, executor, timeouts);
    }

    private record Instance(SchedulerLoop loop, ThreadPoolTaskExecutor executor,
                            ScheduledExecutorService timeouts) {
        void close() {
            loop.stop();
            executor.shutdown();
            timeouts.shutdownNow();
        }
    }
}
