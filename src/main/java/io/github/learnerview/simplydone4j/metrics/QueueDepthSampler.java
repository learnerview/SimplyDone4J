package io.github.learnerview.simplydone4j.metrics;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Samples queue and dead-letter depth onto gauges.
 *
 * <p>Deliberately a scheduled sampler rather than a {@code Gauge} backed by
 * {@code queueSize(...)}: a scrape-time gauge would issue one Redis round trip per
 * priority on every actuator scrape, so a slow or unreachable Redis would block the
 * scrape endpoint itself. Sampling on a timer keeps observability traffic bounded and
 * independent of whoever is polling Prometheus.</p>
 *
 * <p>A failed sample is logged and skipped rather than propagated. Metrics must never be
 * the reason a worker thread dies, and a stale last-known value beats a gap.</p>
 */
public final class QueueDepthSampler {

    private static final Logger log = LoggerFactory.getLogger(QueueDepthSampler.class);

    private final QueueRepository queueRepo;
    private final JobRepository jobRepo;
    private final boolean registered;
    private final AtomicLongArray depthByPriority = new AtomicLongArray(JobPriority.values().length);
    private final AtomicLong lastTotal = new AtomicLong();
    private final AtomicLong lastDeadLetter = new AtomicLong();

    public QueueDepthSampler(QueueRepository queueRepo, JobRepository jobRepo, MeterRegistry registry) {
        this.queueRepo = queueRepo;
        this.jobRepo = jobRepo;
        this.registered = registry != null;
        if (!registered) {
            log.debug("No MeterRegistry available; queue depth gauges will not be published");
            return;
        }

        Gauge.builder("simplydone4j.queue.depth", lastTotal, AtomicLong::doubleValue)
                .description("Jobs waiting across every priority queue")
                .tag("priority", "all")
                .register(registry);
        Gauge.builder("simplydone4j.jobs.dead.letter", lastDeadLetter, AtomicLong::doubleValue)
                .description("Jobs parked in the dead-letter state")
                .register(registry);
        for (JobPriority priority : JobPriority.values()) {
            Gauge.builder("simplydone4j.queue.depth", this, s -> s.depth(priority))
                    .description("Jobs waiting in each priority queue")
                    .tag("priority", priority.name())
                    .register(registry);
        }
    }

    private double depth(JobPriority priority) {
        return depthByPriority.get(priority.ordinal());
    }

    @Scheduled(
            initialDelayString = "#{${simplydone4j.metrics.queue-depth-refresh-seconds:30} * 1000}",
            fixedDelayString = "#{${simplydone4j.metrics.queue-depth-refresh-seconds:30} * 1000}"
    )
    public void sample() {
        if (!registered) return;
        try {
            JobPriority[] priorities = JobPriority.values();
            long total = 0L;
            for (int i = 0; i < priorities.length; i++) {
                long size = queueRepo.queueSize(priorities[i]);
                depthByPriority.set(i, size);
                total += size;
            }
            lastTotal.set(total);
            lastDeadLetter.set(jobRepo.countByStatus(JobStatus.DLQ));
        } catch (Exception e) {
            log.warn("Queue depth sample failed; keeping last known values", e);
        }
    }
}
