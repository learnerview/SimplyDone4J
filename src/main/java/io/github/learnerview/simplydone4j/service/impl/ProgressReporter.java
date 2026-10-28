package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.handler.JobContext;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Writes what the handler reports about its own progress, throttled.
 *
 * <p>A handler that reports progress in a tight loop would otherwise turn every report
 * into a Redis round trip, and the job would spend more time writing its progress than
 * doing its work. At most one write per second reaches the store; the value that is
 * written is the latest one, so the record never shows a stale step. The first report is
 * written straight away, because a job that reports once and finishes should still be
 * visible as having made progress.</p>
 *
 * <p>A failed write is logged and dropped: progress is a report, and a job that cannot
 * report its progress must still be allowed to finish.</p>
 */
final class ProgressReporter implements JobContext.ProgressCallback {

    private static final Logger log = LoggerFactory.getLogger(ProgressReporter.class);
    private static final long MIN_INTERVAL_MS = 1000L;

    private final String jobId;
    private final JobRepository jobRepo;
    private final AtomicLong lastWriteAt = new AtomicLong(0L);
    private final AtomicReference<Double> lastPercent = new AtomicReference<>(0.0);
    private final AtomicReference<String> lastMessage = new AtomicReference<>(null);

    ProgressReporter(String jobId, JobRepository jobRepo) {
        this.jobId = jobId;
        this.jobRepo = jobRepo;
    }

    @Override
    public void update(double percent, String message) {
        double clamped = Math.max(0.0, Math.min(100.0, percent));
        lastPercent.set(clamped);
        lastMessage.set(message);

        long now = System.currentTimeMillis();
        long last = lastWriteAt.get();
        if (now - last < MIN_INTERVAL_MS) {
            return;
        }
        if (!lastWriteAt.compareAndSet(last, now)) {
            return;
        }
        write(clamped, message);
    }

    @Override
    public double progress() {
        return lastPercent.get();
    }

    private void write(double percent, String message) {
        try {
            jobRepo.updateProgress(jobId, percent, message);
        } catch (RuntimeException e) {
            log.warn("Could not record progress for job {}", jobId, e);
        }
    }
}
