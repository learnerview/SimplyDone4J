package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobExecutorServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;

    HandlerRegistry handlerRegistry = new HandlerRegistry();
    JobExecutorServiceImpl service;
    ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-worker-");
        executor.initialize();
        service = new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                executor, 30);
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

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).logSuccess(any(), anyString(), anyLong());
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
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailure(any(), anyString(), anyLong());
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
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailure(any(), anyString(), anyLong());
    }
}
