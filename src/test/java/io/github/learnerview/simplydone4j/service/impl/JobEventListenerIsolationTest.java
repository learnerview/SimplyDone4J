package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventData;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A misbehaving application listener must not be able to change a job's outcome.
 *
 * <p>Uses a real {@link JobEventPublisher} over a deliberately broken
 * {@link ApplicationEventPublisher} rather than a mock, because the mock-based tests
 * elsewhere stub the wrapper out and so can never observe what a listener does to the
 * calls that follow the publish.
 */
@ExtendWith(MockitoExtension.class)
class JobEventListenerIsolationTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock RetryService retryService;
    @Mock WebhookService webhookService;

    HandlerRegistry handlerRegistry = new HandlerRegistry();
    ThreadPoolTaskExecutor executor;
    ScheduledExecutorService timeoutScheduler;

    /** A listener that always fails, standing in for any buggy or absent bean. */
    private static final ApplicationEventPublisher BROKEN_LISTENER = event -> {
        throw new IllegalStateException("listener blew up");
    };

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("test-worker-");
        executor.initialize();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        timeoutScheduler.shutdownNow();
        executor.shutdown();
    }

    private JobExecutorServiceImpl service() {
        return new JobExecutorServiceImpl(jobRepo, queueRepo, retryService, handlerRegistry,
                new JobEventPublisher(BROKEN_LISTENER), webhookService, executor, timeoutScheduler, 30);
    }

    private JobEntity job() {
        return JobEntity.builder()
                .id("job-1").jobType("test").producer("producer-1")
                .status(JobStatus.QUEUED).priority(JobPriority.NORMAL).payload("{}")
                .attemptCount(0).maxAttempts(3).leaseToken("tok-1")
                .build();
    }

    @Test
    @DisplayName("publishing must not propagate a listener failure to the caller")
    void shouldNotPropagateListenerFailure() {
        JobEventPublisher publisher = new JobEventPublisher(BROKEN_LISTENER);
        assertDoesNotThrow(() -> publisher.publish(
                        io.github.learnerview.simplydone4j.event.JobEvent.JOB_CREATED,
                        JobEventData.from(job())),
                "publish is an observer notification. Letting it throw means a listener can "
                        + "reject a submission that was already accepted and persisted.");
    }

    @Test
    @DisplayName("a broken listener must not stop the success webhook or unblock the scheduler")
    void shouldStillFireWebhookAndReturnFromExecute() throws Exception {
        JobHandler handler = org.mockito.Mockito.mock(JobHandler.class);
        when(handler.handle(any())).thenReturn("ok");
        handlerRegistry.register("test", handler);

        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job()));
        when(jobRepo.saveIfLeaseHeld(any(), eq("tok-1"))).thenReturn(true);

        CountDownLatch webhookFired = new CountDownLatch(1);
        doAnswer(inv -> {
            webhookFired.countDown();
            return null;
        }).when(webhookService).fireCallback(any(), anyString(), any());

        // execute() publishes JOB_STARTED from the @Scheduled thread before submitting.
        // If that publish propagates, poll() aborts mid-batch and the job spins: claimed,
        // listener throws, restored, claimed again -- forever, never executing.
        assertDoesNotThrow(() -> service().execute(job()),
                "execute() runs on the scheduler thread; a throwing listener here aborts the "
                        + "poll loop and turns the job into a poison pill");

        assertTrue(webhookFired.await(5, TimeUnit.SECONDS),
                "the webhook is fired after JOB_COMPLETED is published, so a throwing listener "
                        + "silently dropped the callback for a job that had already succeeded");
        verify(retryService).logSuccess(any(), anyString(), anyLong());
    }

    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}