package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
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
import io.github.learnerview.simplydone4j.service.impl.JobExecutorServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.JobSubmissionServiceImpl;
import io.github.learnerview.simplydone4j.service.impl.RetryServiceImpl;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("comprehensive")
@MockitoSettings(strictness = Strictness.LENIENT)
class ComprehensiveEdgeCaseTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RateLimiterService rateLimiter;
    @Mock JobEventPublisher eventPublisher;
    @Mock JobExecutionLogRepository logRepo;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock RetryService retryService;

    SimplyDoneProperties props;
    JobMapper jobMapper;
    HandlerRegistry handlerRegistry;
    Validator validator;

    JobSubmissionService submissionService;
    JobExecutorService executorService;
    ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        jobMapper = new JobMapper(new com.fasterxml.jackson.databind.ObjectMapper());
        handlerRegistry = new HandlerRegistry();
        when(redis.opsForValue()).thenReturn(valueOps);
        validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("comprehensive-");
        executor.initialize();

        executorService = new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry,
                eventPublisher, executor, props.getExecutor().getDefaultTimeoutSeconds());

        submissionService = new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter,
                props, jobMapper, eventPublisher, redis, validator);
    }

    @Nested
    class SubmissionEdgeCases {
        @Test
        void shouldRejectJobWithNullJobType() {
            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setIdempotencyKey("key-1");

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldRejectJobWithNullIdempotencyKey() {
            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldRejectJobWithEmptyJobType() {
            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("");
            req.setIdempotencyKey("key-1");

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldRejectJobWithEmptyIdempotencyKey() {
            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("");

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldRejectJobWithInvalidMaxAttempts() {
            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("key-1");
            req.setMaxAttempts(0);

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldHandleIdempotencyLockContentionGracefully() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                    .thenReturn(false);
            when(valueOps.get(anyString())).thenReturn("job-orphan");
            when(jobRepo.findById("job-orphan")).thenReturn(Optional.empty());

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("key-1");

            assertThrows(IllegalStateException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldThrowWhenIdempotencyKeyExistsButJobNotFound() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                    .thenReturn(false);
            when(valueOps.get(anyString())).thenReturn("job-orphan");
            when(jobRepo.findById("job-orphan")).thenReturn(Optional.empty());

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("key-1");

            assertThrows(IllegalStateException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldSubmitWithCustomTimeout() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("key-timeout");
            req.setTimeoutSeconds(15);

            submissionService.submit("test-app", req);

            ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
            verify(jobRepo).save(captor.capture());
            assertEquals(15, captor.getValue().getTimeoutSeconds().intValue());
        }

        @Test
        void shouldSubmitWithCustomPriority() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("key-priority");
            req.setPriority("HIGH");

            submissionService.submit("test-app", req);

            ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
            verify(jobRepo).save(captor.capture());
            assertEquals(JobPriority.HIGH, captor.getValue().getPriority());
        }
    }

    @Nested
    class ExecutionEdgeCases {
        @Test
        void shouldHandleNoHandlerRegisteredForJobType() throws Exception {
            JobEntity job = JobEntity.builder()
                    .id("no-handler-job")
                    .jobType("undefined-handler")
                    .producer("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .payload("{}")
                    .attemptCount(0)
                    .maxAttempts(3)
                    .leaseToken("tok-1")
                    .build();

            when(jobRepo.findById("no-handler-job")).thenReturn(Optional.of(job));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(inv -> {
                latch.countDown();
                return "RETRY_SCHEDULED";
            }).when(retryService).handleFailure(any(), contains("No handler registered"), anyLong());

            executorService.execute(job);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            verify(retryService).handleFailure(any(), contains("No handler registered"), anyLong());
        }

        @Test
        void shouldHandleLeaseFencingTokenMismatch() throws Exception {
            handlerRegistry.register("fencing-test", ctx -> null);

            JobEntity originalJob = JobEntity.builder()
                    .id("fencing-job")
                    .jobType("fencing-test")
                    .producer("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .leaseToken("original-token")
                    .leaseOwner("worker-1")
                    .payload("{}")
                    .attemptCount(0)
                    .maxAttempts(3)
                    .build();

            JobEntity currentJob = JobEntity.builder()
                    .id("fencing-job")
                    .jobType("fencing-test")
                    .producer("test")
                    .status(JobStatus.RUNNING)
                    .priority(JobPriority.NORMAL)
                    .leaseToken("stolen-token")
                    .leaseOwner("worker-2")
                    .payload("{}")
                    .attemptCount(0)
                    .maxAttempts(3)
                    .build();

            when(jobRepo.findById("fencing-job")).thenReturn(Optional.of(currentJob));

            executorService.execute(originalJob);
            Thread.sleep(500);

            verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
            verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
        }
    }

    @Nested
    class CancelEdgeCases {
        @Test
        void shouldThrowWhenCancellingNonExistentJob() {
            when(jobRepo.findById("ghost-job")).thenReturn(Optional.empty());

            assertThrows(JobNotFoundException.class,
                    () -> submissionService.cancelJob("ghost-job"));
        }

        @Test
        void shouldThrowWhenCancellingRunningJob() {
            JobEntity job = JobEntity.builder()
                    .id("running-job")
                    .status(JobStatus.RUNNING)
                    .build();
            when(jobRepo.findById("running-job")).thenReturn(Optional.of(job));

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.cancelJob("running-job"));
        }

        @Test
        void shouldThrowWhenCancellingAlreadyCancelledJob() {
            JobEntity job = JobEntity.builder()
                    .id("cancelled-job")
                    .status(JobStatus.CANCELLED)
                    .build();
            when(jobRepo.findById("cancelled-job")).thenReturn(Optional.of(job));

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.cancelJob("cancelled-job"));
        }

        @Test
        void shouldThrowWhenCancellingDlqJob() {
            JobEntity job = JobEntity.builder()
                    .id("dlq-job")
                    .status(JobStatus.DLQ)
                    .build();
            when(jobRepo.findById("dlq-job")).thenReturn(Optional.of(job));

            assertThrows(IllegalArgumentException.class,
                    () -> submissionService.cancelJob("dlq-job"));
        }

        @Test
        void shouldFireCancelledEvent() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("cancel-event-test");

            submissionService.submit("test-app", req);

            ArgumentCaptor<JobEntity> captor = ArgumentCaptor.forClass(JobEntity.class);
            verify(jobRepo).save(captor.capture());
            JobEntity job = captor.getValue();

            when(jobRepo.findById(job.getId())).thenReturn(Optional.of(job));

            reset(eventPublisher);
            submissionService.cancelJob(job.getId());

            verify(eventPublisher).publish(eq(JobEvent.JOB_CANCELLED), any(JobEventData.class));
        }
    }

    @Nested
    class QueueEdgeCases {
        @Test
        void shouldThrowWhenQueueIsFullEvenForHighPriority() {
            props.getQueue().setMaxDepth(1);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(2L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("full-queue-high");
            req.setPriority("HIGH");

            assertThrows(QueueFullException.class,
                    () -> submissionService.submit("test-app", req));
        }

        @Test
        void shouldHandleZeroMaxDepth() {
            props.getQueue().setMaxDepth(0);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(1L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("zero-depth");

            assertThrows(QueueFullException.class,
                    () -> submissionService.submit("test-app", req));
        }
    }

    @Nested
    class EventPublishingEdgeCases {
        @Test
        void shouldFireCreatedEventOnSubmission() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("event-created-test");

            submissionService.submit("test-app", req);

            verify(eventPublisher).publish(eq(JobEvent.JOB_CREATED), any(JobEventData.class));
        }

        @Test
        void shouldFireStartedEventOnExecution() {
            handlerRegistry.register("event-start-test", ctx -> null);

            JobEntity job = JobEntity.builder()
                    .id("event-start-job")
                    .jobType("event-start-test")
                    .producer("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .payload("{}")
                    .attemptCount(0)
                    .maxAttempts(3)
                    .leaseToken("tok-1")
                    .build();

            when(jobRepo.findById("event-start-job")).thenReturn(Optional.of(job));

            executorService.execute(job);

            verify(eventPublisher).publish(eq(JobEvent.JOB_STARTED), any(JobEventData.class));
        }
    }

    @Nested
    class ConcurrencyEdgeCases {
        @Test
        void shouldHandleConcurrentSubmissionsWithSameIdempotencyKey() {
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                    .thenReturn(true);
            when(queueRepo.queueSize(any(JobPriority.class))).thenReturn(0L);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("concurrent-key");

            submissionService.submit("test-app", req);

            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                    .thenReturn(false);
            when(valueOps.get(anyString())).thenReturn("existing-job-id");

            JobEntity existing = JobEntity.builder()
                    .id("existing-job-id")
                    .jobType("test")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .build();
            when(jobRepo.findById("existing-job-id")).thenReturn(Optional.of(existing));

            JobSubmissionResponse response = submissionService.submit("test-app", req);
            assertEquals("existing-job-id", response.getJobId());
            verify(jobRepo, times(1)).save(any());
        }

        @Test
        void shouldHandleConcurrentQueueDepthChecks() {
            props.getQueue().setMaxDepth(10);
            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(3L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(3L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(3L);
            when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                    .thenReturn(true);

            JobSubmissionRequest req = new JobSubmissionRequest();
            req.setJobType("test");
            req.setIdempotencyKey("depth-test");

            submissionService.submit("test-app", req);

            when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(4L);
            when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(4L);
            when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(4L);

            JobSubmissionRequest req2 = new JobSubmissionRequest();
            req2.setJobType("test");
            req2.setIdempotencyKey("depth-test-2");

            assertThrows(QueueFullException.class,
                    () -> submissionService.submit("test-app", req2));
        }
    }

    @Nested
    class JobRetrievalEdgeCases {
        @Test
        void shouldThrowWhenGettingNonExistentJob() {
            when(jobRepo.findById("nonexistent")).thenReturn(Optional.empty());

            assertThrows(JobNotFoundException.class,
                    () -> submissionService.getJob("nonexistent"));
        }

        @Test
        void shouldRetrieveJobSuccessfully() {
            JobEntity entity = JobEntity.builder()
                    .id("retrieve-test")
                    .jobType("email")
                    .producer("app")
                    .status(JobStatus.SUCCESS)
                    .priority(JobPriority.HIGH)
                    .build();
            when(jobRepo.findById("retrieve-test")).thenReturn(Optional.of(entity));

            var response = submissionService.getJob("retrieve-test");
            assertEquals("retrieve-test", response.getId());
            assertEquals("SUCCESS", response.getStatus());
            assertEquals("HIGH", response.getPriority());
        }
    }
}
