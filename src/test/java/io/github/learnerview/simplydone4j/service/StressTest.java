package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("stress")
class StressTest {
    private static final Logger log = LoggerFactory.getLogger(StressTest.class);

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RateLimiterService rateLimiter;
    @Mock JobEventPublisher eventPublisher;
    @Mock JobExecutionLogRepository logRepo;
    @Mock org.springframework.data.redis.core.StringRedisTemplate redis;
    @Mock org.springframework.data.redis.core.ValueOperations<String, String> valueOps;
    @Mock RetryService retryService;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobMapper jobMapper = new JobMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    HandlerRegistry handlerRegistry = new HandlerRegistry();
    Validator validator;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    }

    private JobExecutorService createExecutorService(int core, int max, int queueCap) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCap);
        executor.setKeepAliveSeconds(10);
        executor.setThreadNamePrefix("stress-worker-");
        executor.initialize();
        return new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry,
                eventPublisher, executor, 30);
    }

    @Test
    void shouldHandleConcurrentJobExecutions() throws Exception {
        executorService = createExecutorService(80, 160, 400);
        handlerRegistry.register("fast-handler", ctx -> {
            Thread.sleep(10);
        });

        int jobCount = 50;
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        doAnswer(inv -> {
            completed.incrementAndGet();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        doAnswer(inv -> {
            failed.incrementAndGet();
            return null;
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        ConcurrentHashMap<String, JobEntity> jobMap = new ConcurrentHashMap<>();
        when(jobRepo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(jobMap.get(inv.<String>getArgument(0))));

        CountDownLatch allSubmitted = new CountDownLatch(1);
        ExecutorService submitter = Executors.newFixedThreadPool(4);

        for (int i = 0; i < jobCount; i++) {
            final int idx = i;
            submitter.submit(() -> {
                try {
                    allSubmitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String jobId = "stress-job-" + idx;
                JobEntity job = JobEntity.builder()
                        .id(jobId)
                        .jobType("fast-handler")
                        .producer("stress-test")
                        .status(JobStatus.QUEUED)
                        .priority(JobPriority.NORMAL)
                        .payload("{}")
                        .attemptCount(0)
                        .maxAttempts(3)
                        .leaseToken("tok-" + idx)
                        .leaseOwner("worker-" + (idx % 10))
                        .build();
                jobMap.put(jobId, job);
                executorService.execute(job);
            });
        }

        allSubmitted.countDown();
        submitter.shutdown();
        assertTrue(submitter.awaitTermination(30, TimeUnit.SECONDS));

        executor.getThreadPoolExecutor().shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(60, TimeUnit.SECONDS));

        log.info("Stress test completed: {} succeeded, {} failed out of {}",
                completed.get(), failed.get(), jobCount);

        assertEquals(jobCount, completed.get() + failed.get(),
                "All jobs should have a terminal outcome");
    }

    @Test
    void shouldHandleMixedSuccessAndFailureUnderLoad() throws Exception {
        executorService = createExecutorService(80, 160, 400);
        handlerRegistry.register("sometimes-fails", ctx -> {
            String jobId = ctx.getJobId();
            int idx = Integer.parseInt(jobId.replace("mixed-job-", ""));
            if (idx % 5 == 0) {
                throw new RuntimeException("Simulated failure for job " + jobId);
            }
            Thread.sleep(5);
        });

        int jobCount = 30;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        doAnswer(inv -> {
            successCount.incrementAndGet();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        doAnswer(inv -> {
            failureCount.incrementAndGet();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        ConcurrentHashMap<String, JobEntity> jobMap = new ConcurrentHashMap<>();
        when(jobRepo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(jobMap.get(inv.<String>getArgument(0))));

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch latch = new CountDownLatch(1);

        for (int i = 0; i < jobCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String jobId = "mixed-job-" + idx;
                JobEntity job = JobEntity.builder()
                        .id(jobId)
                        .jobType("sometimes-fails")
                        .producer("stress-test")
                        .status(JobStatus.QUEUED)
                        .priority(idx % 3 == 0 ? JobPriority.HIGH : JobPriority.NORMAL)
                        .payload("{}")
                        .attemptCount(0)
                        .maxAttempts(2)
                        .timeoutSeconds(30)
                        .leaseToken("tok-" + idx)
                        .leaseOwner("worker-" + (idx % 10))
                        .build();
                jobMap.put(jobId, job);
                executorService.execute(job);
            });
        }

        latch.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));

        executor.getThreadPoolExecutor().shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(60, TimeUnit.SECONDS));

        int totalDispatched = successCount.get() + failureCount.get();
        log.info("Mixed load test: {} success, {} failures out of {} dispatched",
                successCount.get(), failureCount.get(), totalDispatched);

        assertEquals(jobCount, totalDispatched,
                "All submitted jobs should be dispatched");
    }

    @Test
    void shouldHandleVariableDurationsUnderLoad() throws Exception {
        executorService = createExecutorService(80, 160, 400);
        handlerRegistry.register("variable-handler", ctx -> {
            Thread.sleep(5);
        });

        int jobCount = 10;
        CountDownLatch completionLatch = new CountDownLatch(jobCount);
        AtomicInteger completed = new AtomicInteger(0);

        doAnswer(inv -> {
            completed.incrementAndGet();
            completionLatch.countDown();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        ConcurrentHashMap<String, JobEntity> jobMap = new ConcurrentHashMap<>();
        when(jobRepo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(jobMap.get(inv.<String>getArgument(0))));

        CountDownLatch allStarted = new CountDownLatch(1);
        ExecutorService submitter = Executors.newFixedThreadPool(4);

        for (int i = 0; i < jobCount; i++) {
            final int idx = i;
            submitter.submit(() -> {
                try {
                    allStarted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String jobId = "var-job-" + idx;
                JobEntity job = JobEntity.builder()
                        .id(jobId)
                        .jobType("variable-handler")
                        .producer("stress-test")
                        .status(JobStatus.QUEUED)
                        .priority(idx < 4 ? JobPriority.HIGH : JobPriority.NORMAL)
                        .payload("{}")
                        .attemptCount(0)
                        .maxAttempts(3)
                        .leaseToken("tok-" + idx)
                        .leaseOwner("worker-" + (idx % 10))
                        .build();
                jobMap.put(jobId, job);
                executorService.execute(job);
            });
        }

        allStarted.countDown();
        submitter.shutdown();
        assertTrue(submitter.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(completionLatch.await(30, TimeUnit.SECONDS),
                "All jobs should complete within timeout");

        executor.getThreadPoolExecutor().shutdown();
        executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);

        log.info("Variable duration stress test: {} completed out of {}", completed.get(), jobCount);
        assertEquals(jobCount, completed.get(), "All variable-duration jobs should complete");
    }

    @Test
    void shouldHandleTimeoutUnderLoad() throws Exception {
        executorService = createExecutorService(80, 160, 400);
        handlerRegistry.register("timeout-prone", ctx -> {
            String jobId = ctx.getJobId();
            int idx = Integer.parseInt(jobId.replace("timeout-job-", ""));
            if (idx % 4 == 0) {
                Thread.sleep(5000);
            } else {
                Thread.sleep(5);
            }
        });

        int jobCount = 40;
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        doAnswer(inv -> {
            completed.incrementAndGet();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        doAnswer(inv -> {
            failed.incrementAndGet();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        ConcurrentHashMap<String, JobEntity> jobMap = new ConcurrentHashMap<>();
        when(jobRepo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(jobMap.get(inv.<String>getArgument(0))));

        ExecutorService pool = Executors.newFixedThreadPool(6);

        for (int i = 0; i < jobCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                String jobId = "timeout-job-" + idx;
                JobEntity job = JobEntity.builder()
                        .id(jobId)
                        .jobType("timeout-prone")
                        .producer("stress-test")
                        .status(JobStatus.QUEUED)
                        .priority(JobPriority.NORMAL)
                        .payload("{}")
                        .attemptCount(0)
                        .maxAttempts(1)
                        .timeoutSeconds(idx % 4 == 0 ? 1 : 30)
                        .leaseToken("tok-" + idx)
                        .leaseOwner("worker-" + (idx % 10))
                        .build();
                jobMap.put(jobId, job);
                executorService.execute(job);
            });
        }

        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        executor.getThreadPoolExecutor().shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(60, TimeUnit.SECONDS));

        int totalProcessed = completed.get() + failed.get();
        log.info("Timeout stress test: {} completed, {} failed out of {}", completed.get(), failed.get(), totalProcessed);
        assertEquals(jobCount, totalProcessed, "All timeout-prone jobs should be processed");
    }

    private ThreadPoolTaskExecutor executor;
    private JobExecutorService executorService;
}
