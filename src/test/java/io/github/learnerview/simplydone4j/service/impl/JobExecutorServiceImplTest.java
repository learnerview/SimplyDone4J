package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.JobSignal;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenReturn(true);

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

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailureIfLeaseHeld(any(), eq("token-1"), anyString(), anyLong());

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

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return "RETRY_SCHEDULED";
        }).when(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"), anyString(), anyLong());

        service.execute(job);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"), anyString(), anyLong());
    }

    @Test
    void shouldDiscardSuccessWhenTheLeaseWasLost() {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        JobEntity job = job("job-1", "tok-1");
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenReturn(false);

        service.execute(job);

        verify(jobRepo, timeout(3_000)).saveIfLeaseHeld(any(), eq("tok-1"));
        verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
    }

    @Test
    void shouldDiscardFailureWhenTheLeaseWasLost() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        doThrow(new RuntimeException("boom")).when(handler).handle(any());
        JobEntity job = job("job-1", "tok-1");
        when(retryService.handleFailureIfLeaseHeld(any(), eq("tok-1"), anyString(), anyLong()))
                .thenReturn(null);

        service.execute(job);

        verify(retryService, timeout(3_000))
                .handleFailureIfLeaseHeld(any(), eq("tok-1"), anyString(), anyLong());
    }

    @Test
    void shouldUseTheUnfencedWriteWhenTheJobHasNoLease() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        JobEntity job = job("job-1", null);
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            done.countDown();
            return null;
        }).when(retryService).logSuccess(any(), anyString(), anyLong());

        service.execute(job);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        verify(jobRepo).save(job);
        verify(jobRepo, never()).saveIfLeaseHeld(any(), anyString());
    }

    @Test
    void shouldUseTheUnfencedFailurePathWhenTheJobHasNoLease() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        doThrow(new RuntimeException("boom")).when(handler).handle(any());
        JobEntity job = job("job-1", null);
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            done.countDown();
            return "DLQ";
        }).when(retryService).handleFailure(any(), anyString(), anyLong());

        service.execute(job);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        verify(retryService).handleFailure(any(), anyString(), anyLong());
        verify(retryService, never()).handleFailureIfLeaseHeld(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void shouldInterruptAndFailAHandlerThatExceedsItsTimeout() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        when(handler.handle(any())).thenAnswer(inv -> {
            Thread.sleep(10_000);
            return "late";
        });
        JobEntity job = job("job-1", "tok-1");
        job.setTimeoutSeconds(1);

        ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch failed = new CountDownLatch(1);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            doAnswer(inv -> {
                failed.countDown();
                return "RETRY_SCHEDULED";
            }).when(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"),
                    message.capture(), anyLong());
            new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                    executor, 30, new WorkerCapacity(4), mock(JobSignal.class),
                    null, timeoutScheduler).execute(job);

            assertTrue(failed.await(5, TimeUnit.SECONDS), "the watchdog must interrupt the handler");
            assertTrue(message.getValue().contains("timed out after 1s"), message.getValue());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void shouldKeepTimedOutHandlerRunningWhenItIgnoresInterruption() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(handler.handle(any())).thenAnswer(inv -> {
            started.countDown();
            while (true) {
                try {
                    release.await();
                    return "late";
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
        });
        JobEntity job = job("job-1", "tok-1");
        job.setTimeoutSeconds(1);

        ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch failed = new CountDownLatch(1);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            doAnswer(inv -> {
                failed.countDown();
                return "RETRY_SCHEDULED";
            }).when(retryService).handleFailureIfLeaseHeld(any(), eq("tok-1"),
                    message.capture(), anyLong());
            new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                    executor, 30, null, JobSignal.NOOP,
                    null, timeoutScheduler).execute(job);

            assertTrue(started.await(5, TimeUnit.SECONDS), "the handler must start");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS),
                    "the watchdog must interrupt the handler");
            assertEquals(1, failed.getCount(),
                    "timeout cannot be recorded while the handler ignores interruption");

            release.countDown();
            assertTrue(failed.await(5, TimeUnit.SECONDS), "timeout must be recorded after return");
            assertTrue(message.getValue().contains("timed out after 1s"), message.getValue());
            verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
        } finally {
            release.countDown();
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void shouldNotReleaseCapacityUntilTheOutcomeIsPersisted() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        JobEntity job = job("job-1", "tok-1");
        WorkerCapacity capacity = new WorkerCapacity(1);
        assertTrue(capacity.tryAcquire(), "precondition: the scheduler holds one in-flight permit");
        CountDownLatch outcomeWritten = new CountDownLatch(1);
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenAnswer(inv -> {
            assertEquals(0, capacity.availablePermits(),
                    "the permit must stay held until the terminal write returns");
            outcomeWritten.countDown();
            return true;
        });
        JobSignal signal = mock(JobSignal.class);
        CountDownLatch signalled = new CountDownLatch(1);
        doAnswer(inv -> {
            signalled.countDown();
            return null;
        }).when(signal).signal();

        new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                executor, 30, capacity, signal, null, null).execute(job);

        assertTrue(outcomeWritten.await(5, TimeUnit.SECONDS));
        assertTrue(signalled.await(5, TimeUnit.SECONDS));
        assertEquals(1, capacity.availablePermits());
    }

    @Test
    void shouldReleaseCapacityWhenTheOutcomeWriteFails() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        JobEntity job = job("job-1", "tok-1");
        WorkerCapacity capacity = new WorkerCapacity(1);
        assertTrue(capacity.tryAcquire(), "precondition: the scheduler holds one in-flight permit");
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1")))
                .thenThrow(new IllegalStateException("redis unavailable"));
        JobSignal signal = mock(JobSignal.class);
        CountDownLatch signalled = new CountDownLatch(1);
        doAnswer(inv -> {
            signalled.countDown();
            return null;
        }).when(signal).signal();

        new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                executor, 30, capacity, signal, null, null).execute(job);

        assertTrue(signalled.await(5, TimeUnit.SECONDS));
        assertEquals(1, capacity.availablePermits(),
                "a failed terminal write must not leak the in-flight permit");
    }

    @Test
    void shouldReleaseCapacityAndSignalWhenExecutionFinishes() throws Exception {
        JobHandler handler = mock(JobHandler.class);
        handlerRegistry.register("test", handler);
        JobEntity job = job("job-1", "tok-1");
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenReturn(true);
        WorkerCapacity capacity = new WorkerCapacity(1);
        assertTrue(capacity.tryAcquire(), "precondition: the scheduler holds one in-flight permit");
        JobSignal signal = mock(JobSignal.class);
        CountDownLatch signalled = new CountDownLatch(1);
        doAnswer(inv -> {
            signalled.countDown();
            return null;
        }).when(signal).signal();

        new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                executor, 30, capacity, signal, null, null).execute(job);

        assertTrue(signalled.await(5, TimeUnit.SECONDS));
        assertEquals(1, capacity.availablePermits());
        verify(signal).signal();
    }

    private JobEntity job(String id, String leaseToken) {
        return JobEntity.builder()
                .id(id)
                .jobType("test")
                .producer("producer-1")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .attemptCount(1)
                .maxAttempts(3)
                .leaseToken(leaseToken)
                .build();
    }
}
