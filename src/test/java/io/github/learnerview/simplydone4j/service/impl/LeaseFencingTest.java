package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.RetryService;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaseFencingTest {

    @Mock JobRepository jobRepo;
    @Mock RetryService retryService;
    @Mock JobEventPublisher eventPublisher;
    @Mock WebhookService webhookService;

    HandlerRegistry handlerRegistry = new HandlerRegistry();
    JobExecutorServiceImpl service;
    ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("fencing-test-");
        executor.initialize();
        service = new JobExecutorServiceImpl(jobRepo, retryService, handlerRegistry, eventPublisher,
                webhookService, executor, 30);
    }

    @Test
    void shouldRejectSuccessWhenLeaseTokenIsClearedByReaper() throws Exception {
        CountDownLatch fencingCheckDone = new CountDownLatch(1);
        handlerRegistry.register("slow-job", ctx -> null);

        JobEntity originalJob = JobEntity.builder()
                .id("job-with-expired-lease")
                .jobType("slow-job")
                .producer("worker-a")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken("original-lease-token")
                .leaseOwner("worker-a")
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        JobEntity afterReaper = JobEntity.builder()
                .id("job-with-expired-lease")
                .jobType("slow-job")
                .producer("worker-a")
                .status(JobStatus.RETRY_SCHEDULED)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken(null)
                .leaseOwner(null)
                .attemptCount(1)
                .maxAttempts(3)
                .build();

        when(jobRepo.findById("job-with-expired-lease")).thenAnswer(invocation -> {
            fencingCheckDone.countDown();
            return Optional.of(afterReaper);
        });

        service.execute(originalJob);

        assert fencingCheckDone.await(10, TimeUnit.SECONDS) : "Fencing check did not run";

        verify(retryService, never()).logSuccess(any(), anyString(), anyLong());
    }

    @Test
    void shouldRejectFailureWhenLeaseTokenIsClearedByReaper() throws Exception {
        CountDownLatch fencingCheckDone = new CountDownLatch(1);
        handlerRegistry.register("failing-job", ctx -> {
            throw new RuntimeException("handler failed");
        });

        JobEntity originalJob = JobEntity.builder()
                .id("job-with-expired-lease-fail")
                .jobType("failing-job")
                .producer("worker-a")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken("original-lease-token")
                .leaseOwner("worker-a")
                .attemptCount(0)
                .maxAttempts(3)
                .build();

        JobEntity afterReaper = JobEntity.builder()
                .id("job-with-expired-lease-fail")
                .jobType("failing-job")
                .producer("worker-a")
                .status(JobStatus.DLQ)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .leaseToken(null)
                .leaseOwner(null)
                .attemptCount(2)
                .maxAttempts(3)
                .build();

        when(jobRepo.findById("job-with-expired-lease-fail")).thenAnswer(invocation -> {
            fencingCheckDone.countDown();
            return Optional.of(afterReaper);
        });

        service.execute(originalJob);

        assert fencingCheckDone.await(10, TimeUnit.SECONDS) : "Fencing check did not run";

        verify(retryService, never()).handleFailure(any(), anyString(), anyLong());
    }
}
