package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobHandler;
import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.UniquenessGuard;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobExecutorOverlapTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock HandlerRegistry handlerRegistry;
    @Mock JobEventPublisher eventPublisher;
    @Mock WebhookService webhookService;
    @Mock JobHandler handler;
    @Mock UniquenessGuard guard;

    ThreadPoolTaskExecutor executor;
    ScheduledExecutorService timeoutScheduler;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.initialize();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        timeoutScheduler.shutdownNow();
    }

    private JobExecutorServiceImpl service(JobMetrics metrics) {
        return new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry,
                eventPublisher, webhookService, guard, 5, executor, timeoutScheduler, 30, metrics);
    }

    private JobEntity running(String id, String uniqueKey) {
        return JobEntity.builder()
                .id(id)
                .jobType("reconcile")
                .producer("payments")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .uniqueKey(uniqueKey)
                .leaseOwner("worker-1")
                .leaseToken("token-" + id)
                .attemptCount(1)
                .maxAttempts(3)
                .build();
    }

    @Test
    void shouldRunTheHandlerWhenTheUniqueKeyIsFree() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(guard.tryAcquire(eq("account-42"), eq("job-1"), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        verify(handler, timeout(5000)).handle(any());
        verify(queueRepo, never()).enqueue(anyString(), any(), anyInt());
    }

    @Test
    void shouldReleaseTheKeyWhenTheHandlerFinishes() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        verify(guard, timeout(5000)).release("account-42", "job-1");
    }

    @Test
    void shouldReleaseTheKeyWhenTheHandlerThrows() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(retryService.handleFailureIfLeaseHeld(any(), anyString(), anyString(), any(Long.class)))
                .thenReturn("RETRY_SCHEDULED");

        service(JobMetrics.NOOP).execute(job);

        verify(guard, timeout(5000)).release("account-42", "job-1");
    }

    @Test
    void shouldNotRunTheHandlerWhenTheUniqueKeyIsHeld() throws Exception {
        JobEntity job = running("job-2", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(eq("account-42"), eq("job-2"), any(Duration.class))).thenReturn(false);
        when(jobRepo.findById("job-2")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        // execute() dispatches asynchronously, so wait for the task to reach its
        // observable effect before asserting negatives. Deferring an overlap
        // legitimately persists the job under its lease -- see
        // shouldPutADeferredJobBackOnTheQueueWithoutConsumingAnAttempt -- so the
        // deferral write is awaited here, and only the handler is asserted absent.
        // Asserting never() on saveIfLeaseHeld both contradicted that sibling test and
        // raced the worker thread, which is why it passed locally and failed in CI.
        verify(jobRepo, timeout(5000)).saveIfLeaseHeld(any(), eq("token-job-2"));
        verify(handler, never()).handle(any());
    }

    @Test
    void shouldPutADeferredJobBackOnTheQueueWithoutConsumingAnAttempt() throws Exception {
        JobEntity job = running("job-2", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(jobRepo.findById("job-2")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo, timeout(5000)).saveIfLeaseHeld(saved.capture(), eq("token-job-2"));

        assertEquals(JobStatus.QUEUED, saved.getValue().getStatus());
        // The whole point: an overlap is not a failure, so the budget is untouched.
        assertEquals(1, saved.getValue().getAttemptCount());
        assertTrue(saved.getValue().getNextRunAt().isAfter(Instant.now()),
                "A deferred job must be pushed into the future, not left runnable now");
        assertFalse(saved.getValue().getLeaseToken() != null);
    }

    @Test
    void shouldEnqueueADeferredJobSoItIsActuallyClaimedLater() throws Exception {
        JobEntity job = running("job-2", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(jobRepo.findById("job-2")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        ArgumentCaptor<JobEntity> saved = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo, timeout(5000)).saveIfLeaseHeld(saved.capture(), eq("token-job-2"));

        verify(queueRepo, timeout(5000)).enqueue(eq("job-2"), eq(JobPriority.NORMAL),
                eq(saved.getValue().getNextRunAt().toEpochMilli()));
    }

    @Test
    void shouldNotResurrectADeferredJobWhoseLeaseMovedOn() throws Exception {
        JobEntity job = running("job-2", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(jobRepo.findById("job-2")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(false);

        service(JobMetrics.NOOP).execute(job);

        verify(queueRepo, never()).enqueue(anyString(), any(), anyInt());
    }

    @Test
    void shouldNotHoldTheKeyOpenAfterATimeout() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(retryService.handleFailureIfLeaseHeld(any(), anyString(), anyString(), any(Long.class)))
                .thenReturn("RETRY_SCHEDULED");

        JobExecutorServiceImpl svc = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService,
                handlerRegistry, eventPublisher, webhookService, guard, 5, executor, timeoutScheduler, 1,
                JobMetrics.NOOP);
        svc.execute(job);

        verify(guard, timeout(5000)).release("account-42", "job-1");
    }

    @Test
    void shouldUseTheConfiguredTtlForTheUniquenessLock() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        JobExecutorServiceImpl svc = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService,
                handlerRegistry, eventPublisher, webhookService, guard, 5, 60, executor, timeoutScheduler, 30,
                JobMetrics.NOOP);
        svc.execute(job);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(guard, timeout(5000)).tryAcquire(eq("account-42"), eq("job-1"), ttl.capture());
        assertEquals(60L, ttl.getValue().toSeconds());
    }

    @Test
    void shouldDeriveTheLockTtlFromTheTimeoutWhenNotConfigured() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        JobExecutorServiceImpl svc = new JobExecutorServiceImpl(jobRepo, queueRepo, retryService,
                handlerRegistry, eventPublisher, webhookService, guard, 5, executor, timeoutScheduler, 30,
                JobMetrics.NOOP);
        svc.execute(job);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(guard, timeout(5000)).tryAcquire(eq("account-42"), eq("job-1"), ttl.capture());
        assertEquals(60L, ttl.getValue().toSeconds());
    }

    @Test
    void shouldRunTheJobWhenTheGuardItselfIsBroken() throws Exception {
        JobEntity job = running("job-1", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        // Failing closed would stall the queue on a Redis blip, which is a worse
        // outage than a briefly weaker uniqueness guarantee.
        verify(handler, timeout(5000)).handle(any());
    }

    @Test
    void shouldCountOverlapsAsAMetric() throws Exception {
        JobEntity job = running("job-2", "account-42");
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(guard.tryAcquire(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(jobRepo.findById("job-2")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        AtomicInteger overlaps = new AtomicInteger();
        JobMetrics metrics = new NoopRecordingMetrics() {
            @Override public void recordOverlapped() { overlaps.incrementAndGet(); }
        };

        service(metrics).execute(job);

        verify(jobRepo, timeout(5000)).saveIfLeaseHeld(any(), anyString());
        assertEquals(1, overlaps.get());
    }

    @Test
    void shouldNotQueryTheGuardForAnUnkeyedJob() throws Exception {
        JobEntity job = running("job-1", null);
        when(handlerRegistry.getHandler("reconcile")).thenReturn(handler);
        when(handler.handle(any())).thenReturn("ok");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.saveIfLeaseHeld(any(), anyString())).thenReturn(true);

        service(JobMetrics.NOOP).execute(job);

        verify(handler, timeout(5000)).handle(any());
        // Unkeyed jobs must not pay a Redis round trip, or the default-off
        // configuration would still slow every job down.
        verify(guard, never()).tryAcquire(any(), anyString(), any());
    }

    private static class NoopRecordingMetrics implements JobMetrics {
        @Override public void recordSubmitted(JobPriority priority) { }
        @Override public void recordClaimed(int count) { }
        @Override public void recordCompleted(Outcome outcome, long durationMs) { }
        @Override public void recordFencedWriteRejected() { }
        @Override public void recordPoll(long durationMs, int claimed) { }
        @Override public void recordLeaseReaped() { }
        @Override public void recordOverlapped() { }
        @Override public boolean isEnabled() { return false; }
    }
}
