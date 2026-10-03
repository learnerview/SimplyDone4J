package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobExecutorServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;
    @Mock WebhookService webhookService;

    HandlerRegistry handlerRegistry = new HandlerRegistry();
    JobExecutorServiceImpl service;
    ThreadPoolTaskExecutor executor;
    ScheduledExecutorService timeoutScheduler;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-worker-");
        executor.initialize();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        service = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry, eventPublisher,
                webhookService, executor, timeoutScheduler, 30);
    }

    @AfterEach
    void tearDown() {
        timeoutScheduler.shutdownNow();
    }

    @Test
    void shouldExecuteJobWithHandler() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);

        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenReturn(true);

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).logSuccess(any(), anyString(), anyLong());
    }

    @Test
    void shouldNotLogSuccessWhenTheFencedWriteIsRejected() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);

        JobEntity job = JobEntity.builder()
                .id("job-1").jobType("test").producer("producer-1")
                .status(JobStatus.QUEUED).priority(JobPriority.NORMAL).payload("{}")
                .attemptCount(0).maxAttempts(3).leaseToken("tok-1")
                .build();

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(false);

        service.execute(job);
        Thread.sleep(300);

        verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
    }

    @Test
    void shouldHandleExceptionFromHandler() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);

        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("token-1")
                .build();

        doThrow(new RuntimeException("Handler failed")).when(handler).handle(any());
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        JobEntity rescheduled = JobEntity.builder()
                .id("job-1").jobType("test").producer("producer-1")
                .status(JobStatus.RETRY_SCHEDULED).priority(JobPriority.NORMAL).payload("{}")
                .attemptCount(1).maxAttempts(3).leaseToken("token-1")
                .build();
        when(retryService.handleFailureIfLeaseHeld(any(), anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> {
                    latch.countDown();
                    return rescheduled;
                });

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailureIfLeaseHeld(any(), eq("token-1"), anyString(), anyLong());
    }

    @Test
    void shouldHandleNoHandlerRegistered() throws Exception {
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("unknown")
                .producer("producer-1")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(retryService).handleFailureIfLeaseHeld(any(), anyString(), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"), anyString(), anyLong());
    }
}
