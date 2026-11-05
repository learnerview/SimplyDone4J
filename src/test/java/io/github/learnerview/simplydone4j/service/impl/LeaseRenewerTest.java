package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.repository.JobWorkerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseRenewerTest {

    private JobWorkerRepository jobRepo;
    private ScheduledExecutorService scheduler;
    private LeaseRenewer renewer;

    @BeforeEach
    void setUp() {
        jobRepo = mock(JobWorkerRepository.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getWorker().setLeaseTimeoutSeconds(1);
        renewer = new LeaseRenewer(jobRepo, scheduler, props);
        when(jobRepo.extendLease(anyString(), anyString(), any(Instant.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shouldRenewWhileTheJobRuns() {
        renewer.start("job-1", "token-1");

        verify(jobRepo, timeout(1_500).atLeast(2))
                .extendLease(eq("job-1"), eq("token-1"), any(Instant.class));
    }

    @Test
    void shouldStopRenewingAfterStop() {
        renewer.start("job-1", "token-1");
        verify(jobRepo, timeout(1_500).atLeast(2))
                .extendLease(eq("job-1"), eq("token-1"), any(Instant.class));

        renewer.stop("job-1");
        int calls = mockingDetails(jobRepo).getInvocations().size();

        verify(jobRepo, after(500).times(calls))
                .extendLease(eq("job-1"), eq("token-1"), any(Instant.class));
    }

    @Test
    void shouldStopWhenTheFenceIsLost() {
        when(jobRepo.extendLease(anyString(), anyString(), any(Instant.class))).thenReturn(false);

        renewer.start("job-1", "token-1");

        verify(jobRepo, timeout(1_500).atLeast(1))
                .extendLease(eq("job-1"), eq("token-1"), any(Instant.class));
        verify(jobRepo, after(500).times(1))
                .extendLease(eq("job-1"), eq("token-1"), any(Instant.class));
    }

    @Test
    void stoppingAnOldAttemptMustNotCancelTheNewAttemptsRenewal() {
        renewer.start("job-1", "token-old");
        renewer.start("job-1", "token-new");

        renewer.stop("job-1", "token-old");

        verify(jobRepo, timeout(1_500).atLeast(2))
                .extendLease(eq("job-1"), eq("token-new"), any(Instant.class));
    }

    @Test
    void shouldNotRenewWithoutALeaseToken() {
        renewer.start("job-1", null);

        verify(jobRepo, after(500).never()).extendLease(anyString(), anyString(), any(Instant.class));
        verify(jobRepo, never()).extendLease(anyString(), anyString(), any(Instant.class));
    }
}
