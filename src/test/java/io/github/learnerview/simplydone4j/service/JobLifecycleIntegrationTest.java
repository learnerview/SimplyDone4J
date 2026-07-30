package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.impl.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobLifecycleIntegrationTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock io.github.learnerview.simplydone4j.service.RateLimiterService rateLimiter;
    @Mock JobEventPublisher eventPublisher;
    @Mock JobExecutionLogRepository logRepo;
    @Mock org.springframework.data.redis.core.StringRedisTemplate redis;
    @Mock org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobMapper jobMapper = new JobMapper(new com.fasterxml.jackson.databind.ObjectMapper());
    HandlerRegistry handlerRegistry = new HandlerRegistry();
    ThreadPoolTaskExecutor executor;
    Validator validator;

    JobSubmissionService submissionService;
    JobExecutorService executorService;
    RetryService retryService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        retryService = new RetryServiceImpl(jobRepo, logRepo, props, eventPublisher);

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("lifecycle-test-");
        executor.initialize();

        executorService = new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry,
                eventPublisher, executor, props.getExecutor().getDefaultTimeoutSeconds());

        submissionService = new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter,
                props, jobMapper, eventPublisher, redis, validator);
    }

    @Test
    void shouldSubmitAndExecuteSuccessfully() throws Exception {
        handlerRegistry.register("test-handler", ctx -> {});

        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test-handler");
        req.setIdempotencyKey("lifecycle-1");
        req.setPriority("NORMAL");

        JobSubmissionResponse response = submissionService.submit("test-app", req);
        assertEquals("QUEUED", response.getStatus());

        ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo).save(captor.capture());
        JobEntity job = captor.getValue();
        assertNotNull(job);
        assertEquals(JobStatus.QUEUED, job.getStatus());

        job.setLeaseToken("tok-1");
        job.setLeaseOwner("worker-1");
        when(jobRepo.findById(anyString())).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        lenient().doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(eventPublisher).publish(eq(JobEvent.JOB_COMPLETED), any());

        executorService.execute(job);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Job should complete within timeout");
    }

    @Test
    void shouldHandleFailureAndRetry() throws Exception {
        handlerRegistry.register("failing-handler", ctx -> {
            throw new RuntimeException("Simulated failure");
        });

        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("failing-handler");
        req.setIdempotencyKey("lifecycle-fail-1");
        req.setMaxAttempts(3);

        submissionService.submit("test-app", req);

        ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo).save(captor.capture());
        JobEntity job = captor.getValue();
        job.setAttemptCount(0);
        job.setLeaseToken("tok-1");
        job.setLeaseOwner("worker-1");

        when(jobRepo.findById(job.getId())).thenReturn(Optional.of(job));

        executorService.execute(job);

        long deadline = System.currentTimeMillis() + 10_000;
        while (job.getStatus() != JobStatus.RETRY_SCHEDULED && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(JobStatus.RETRY_SCHEDULED, job.getStatus());
    }

    @Test
    void shouldMoveToDlqAfterMaxRetries() {
        JobEntity job = JobEntity.builder()
                .id("dlq-test-1")
                .jobType("test")
                .producer("test-app")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .attemptCount(3)
                .maxAttempts(3)
                .build();

        String result = retryService.handleFailure(job, "Final error", 100L);

        assertEquals("DLQ", result);
        assertEquals(JobStatus.DLQ, job.getStatus());
        verify(jobRepo).save(job);
    }

    @Test
    void shouldCancelQueuedJob() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("cancel-test-1");

        submissionService.submit("test-app", req);

        ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo).save(captor.capture());
        JobEntity job = captor.getValue();
        when(jobRepo.findById(job.getId())).thenReturn(Optional.of(job));

        submissionService.cancelJob(job.getId());

        assertEquals(JobStatus.CANCELLED, job.getStatus());
        verify(queueRepo).remove(job.getId(), job.getPriority());
    }

    @Test
    void shouldThrowOnCancelNonQueuedJob() {
        JobEntity job = JobEntity.builder()
                .id("running-job")
                .status(JobStatus.RUNNING)
                .build();

        when(jobRepo.findById("running-job")).thenReturn(Optional.of(job));

        assertThrows(IllegalArgumentException.class,
                () -> submissionService.cancelJob("running-job"));
    }

    @Test
    void shouldThrowOnNonexistentJob() {
        when(jobRepo.findById("nonexistent")).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class,
                () -> submissionService.getJob("nonexistent"));
    }

    @Test
    void shouldThrowWhenQueueExceedsMaxDepth() {
        props.getQueue().setMaxDepth(5);
        when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(10L);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("full-queue-1");

        assertThrows(QueueFullException.class,
                () -> submissionService.submit("test-app", req));
    }
}
