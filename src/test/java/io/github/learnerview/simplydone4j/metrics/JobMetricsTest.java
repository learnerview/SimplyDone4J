package io.github.learnerview.simplydone4j.metrics;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JobMetricsTest {

    @Mock QueueRepository queueRepo;
    @Mock JobRepository jobRepo;

    private static double counter(MeterRegistry registry, String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counter().count();
    }

    private static double timerCount(MeterRegistry registry, String name, String outcome) {
        return registry.find(name).tag("outcome", outcome).timer().count();
    }

    private static double timerSeconds(MeterRegistry registry, String name, String outcome) {
        return registry.find(name).tag("outcome", outcome).timer().totalTime(TimeUnit.SECONDS);
    }

    @Test
    void noopRecorderSwallowsEverything() {
        assertDoesNotThrow(() -> {
            JobMetrics.NOOP.recordSubmitted(JobPriority.HIGH);
            JobMetrics.NOOP.recordClaimed(3);
            JobMetrics.NOOP.recordCompleted(JobMetrics.Outcome.SUCCESS, 10L);
            JobMetrics.NOOP.recordFencedWriteRejected();
            JobMetrics.NOOP.recordPoll(1L, 1);
            JobMetrics.NOOP.recordLeaseReaped();
        });
    }

    @Test
    void shouldCountSubmissionsInTotalAndByPriority() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordSubmitted(JobPriority.HIGH);
        metrics.recordSubmitted(JobPriority.HIGH);
        metrics.recordSubmitted(JobPriority.LOW);

        assertEquals(3.0, registry.find("simplydone4j.jobs.submitted").counter().count());
        assertEquals(2.0, counter(registry, "simplydone4j.jobs.submitted.by.priority", "priority", "HIGH"));
        assertEquals(1.0, counter(registry, "simplydone4j.jobs.submitted.by.priority", "priority", "LOW"));
    }

    @Test
    void shouldCountTerminalOutcomesAndDurationsSeparately() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordCompleted(JobMetrics.Outcome.SUCCESS, 100L);
        metrics.recordCompleted(JobMetrics.Outcome.SUCCESS, 300L);
        metrics.recordCompleted(JobMetrics.Outcome.TIMEOUT, 30_000L);
        metrics.recordCompleted(JobMetrics.Outcome.DISCARDED, 5L);

        assertEquals(2.0, counter(registry, "simplydone4j.jobs.completed", "outcome", "SUCCESS"));
        assertEquals(1.0, counter(registry, "simplydone4j.jobs.completed", "outcome", "TIMEOUT"));
        assertEquals(1.0, counter(registry, "simplydone4j.jobs.completed", "outcome", "DISCARDED"));
        assertEquals(2.0, timerCount(registry, "simplydone4j.jobs.duration", "SUCCESS"));
        assertEquals(0.4, timerSeconds(registry, "simplydone4j.jobs.duration", "SUCCESS"), 1e-9);
        assertEquals(30.0, timerSeconds(registry, "simplydone4j.jobs.duration", "TIMEOUT"), 1e-9);
    }

    @Test
    void shouldCountFencingRejectionsSeparatelyFromOutcomes() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordFencedWriteRejected();
        metrics.recordFencedWriteRejected();
        metrics.recordFencedWriteRejected();

        assertEquals(3.0, registry.find("simplydone4j.fencing.rejections").counter().count());
        // A rejected write is not a completed job; counting it under an outcome too
        // would double-report the same event to anyone alerting on either series.
        assertEquals(0.0, counter(registry, "simplydone4j.jobs.completed", "outcome", "DISCARDED"));
    }

    @Test
    void shouldIgnoreNonPositiveClaimCounts() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordClaimed(0);
        metrics.recordClaimed(-5);
        metrics.recordClaimed(4);

        assertEquals(4.0, registry.find("simplydone4j.jobs.claimed").counter().count());
    }

    @Test
    void shouldClampNegativeDurationsRatherThanCorruptingTheTimer() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordCompleted(JobMetrics.Outcome.FAILED, -500L);

        assertEquals(0.0, timerSeconds(registry, "simplydone4j.jobs.duration", "FAILED"), 1e-9);
    }

    @Test
    void shouldRecordPollTimingAndClaimsAsADistribution() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JobMetrics metrics = new MicrometerJobMetrics(registry);

        metrics.recordPoll(12L, 4);
        metrics.recordPoll(20L, 6);

        assertEquals(2.0, registry.find("simplydone4j.scheduler.poll").timer().count());
        assertEquals(6.0, registry.find("simplydone4j.scheduler.claims.per.poll").summary().max(), 1e-9);
        assertEquals(5.0, registry.find("simplydone4j.scheduler.claims.per.poll").summary().mean(), 1e-9);
    }

    @Test
    void queueDepthSamplerShouldPublishPerPriorityGauges() {
        MeterRegistry registry = new SimpleMeterRegistry();
        when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(7L);
        when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(3L);
        when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);
        when(jobRepo.countByStatus(JobStatus.DLQ)).thenReturn(2L);

        QueueDepthSampler sampler = new QueueDepthSampler(queueRepo, jobRepo, registry);
        sampler.sample();

        assertEquals(7.0, registry.find("simplydone4j.queue.depth").tag("priority", "HIGH").gauge().value());
        assertEquals(3.0, registry.find("simplydone4j.queue.depth").tag("priority", "NORMAL").gauge().value());
        assertEquals(0.0, registry.find("simplydone4j.queue.depth").tag("priority", "LOW").gauge().value());
        assertEquals(10.0, registry.find("simplydone4j.queue.depth").tag("priority", "all").gauge().value());
        assertEquals(2.0, registry.find("simplydone4j.jobs.dead.letter").gauge().value());
    }

    @Test
    void queueDepthSamplerShouldKeepLastKnownValuesWhenRedisFails() {
        MeterRegistry registry = new SimpleMeterRegistry();
        when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(4L).thenThrow(new RuntimeException("redis down"));
        when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(1L);
        when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(0L);

        QueueDepthSampler sampler = new QueueDepthSampler(queueRepo, jobRepo, registry);
        sampler.sample();

        // Metrics must never be the reason a scheduled thread dies.
        assertDoesNotThrow(sampler::sample);
        assertEquals(4.0, registry.find("simplydone4j.queue.depth").tag("priority", "HIGH").gauge().value());
    }

    @Test
    void queueDepthSamplerShouldTolerateAMissingMeterRegistry() {
        assertDoesNotThrow(() -> new QueueDepthSampler(queueRepo, jobRepo, null).sample());
    }
}
