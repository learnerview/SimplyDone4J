package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.InvalidPayloadException;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobErrorCode;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every failure has to say why it failed.
 *
 * <p>The status alone cannot: a timeout, a handler that threw and a job type nobody
 * handles are all "FAILED", and telling them apart means reading the message. The error
 * code is the stable, machine-readable answer.</p>
 */
@ExtendWith(MockitoExtension.class)
class JobExecutorErrorCodeTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;

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
                executor, io.github.learnerview.simplydone4j.service.JobExecutorOptions.builder()
                        .timer(timeoutScheduler)
                        .defaultTimeoutSeconds(30)
                        .build());
    }

    @AfterEach
    void tearDown() {
        timeoutScheduler.shutdownNow();
        executor.shutdown();
    }

    private JobEntity job(String type) {
        return JobEntity.builder()
                .id("job-1")
                .jobType(type)
                .producer("producer-1")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(0)
                .maxAttempts(3)
                .leaseToken("tok-1")
                .build();
    }

    @Test
    void shouldReportAHandlerThatThrewAsHandlerThrew() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        doThrow(new RuntimeException("boom")).when(handler).handle(any());

        JobEntity job = job("test");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        verify(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"),
                eq(JobErrorCode.HANDLER_THREW), anyString(), anyLong());
    }

    @Test
    void shouldReportAnUnknownJobTypeAsUnknownJobType() throws Exception {
        JobEntity job = job("nobody-handles-this");

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "FAILED";
        }).when(retryService).handleTerminalFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        verify(retryService).handleTerminalFailureIfLeaseHeld(any(), eq("tok-1"),
                eq(JobErrorCode.UNKNOWN_JOB_TYPE), anyString(), anyLong());
        verify(retryService, never()).handleFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());
    }

    /**
     * A payload the handler cannot read will not read any better on the next attempt, so
     * the job fails terminally and keeps its attempt budget.
     */
    @Test
    void shouldReportAnUnreadablePayloadAsInvalidPayload() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        doThrow(new InvalidPayloadException("field 'amount' is missing"))
                .when(handler).handle(any());

        JobEntity job = job("test");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "FAILED";
        }).when(retryService).handleTerminalFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        verify(retryService).handleTerminalFailureIfLeaseHeld(any(), eq("tok-1"),
                eq(JobErrorCode.PAYLOAD_INVALID), anyString(), anyLong());
        verify(retryService, never()).handleFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());
    }

    @Test
    void shouldReportATimeoutAsTimeout() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        // The handler never returns; the watchdog has to record the timeout.
        doAnswer(inv -> {
            Thread.sleep(5000);
            return null;
        }).when(handler).handle(any());

        JobEntity job = job("test");
        job.setTimeoutSeconds(1);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailureIfLeaseHeld(any(), anyString(),
                any(JobErrorCode.class), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(10, TimeUnit.SECONDS));

        verify(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"),
                eq(JobErrorCode.TIMEOUT), anyString(), anyLong());
    }
}
