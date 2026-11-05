package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.repository.JobWorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a running job's lease alive while its handler is still executing.
 *
 * <p>Without renewal, a handler that runs longer than the fixed lease is reaped and
 * retried while the first copy is still running. Renewal only extends the deadline; the
 * fenced terminal write still decides which execution is allowed to record an outcome.</p>
 */
public final class LeaseRenewer {

    private static final Logger log = LoggerFactory.getLogger(LeaseRenewer.class);

    private final JobWorkerRepository jobRepo;
    private final ScheduledExecutorService scheduler;
    private final Duration leaseDuration;
    private final Map<String, Renewal> renewals = new ConcurrentHashMap<>();

    public LeaseRenewer(JobWorkerRepository jobRepo, ScheduledExecutorService scheduler,
                        SimplyDoneProperties config) {
        this.jobRepo = jobRepo;
        this.scheduler = scheduler;
        this.leaseDuration = Duration.ofSeconds(
                Math.max(1, config.getWorker().getLeaseTimeoutSeconds()));
    }

    public void start(String jobId, String leaseToken) {
        if (leaseToken == null) return;
        long intervalMs = Math.max(250, leaseDuration.toMillis() / 3);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> renew(jobId, leaseToken), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        Renewal previous = renewals.put(jobId, new Renewal(leaseToken, future));
        if (previous != null) previous.future().cancel(false);
    }

    /**
     * Stops the renewal for this attempt only.
     *
     * <p>A superseded worker can finish after the reaper has already started the next
     * attempt for the same job id. Stopping by job id alone would cancel the new attempt's
     * renewal, letting its still-running execution expire and be reaped again.</p>
     */
    public void stop(String jobId, String leaseToken) {
        if (jobId == null || leaseToken == null) return;
        Renewal current = renewals.get(jobId);
        if (current == null || !leaseToken.equals(current.leaseToken())) return;
        if (renewals.remove(jobId, current)) current.future().cancel(false);
    }

    public void stop(String jobId) {
        Renewal current = renewals.remove(jobId);
        if (current != null) current.future().cancel(false);
    }

    private void renew(String jobId, String leaseToken) {
        try {
            boolean extended = jobRepo.extendLease(jobId, leaseToken,
                    Instant.now().plus(leaseDuration));
            if (!extended) {
                log.warn("Lease renewal for job {} lost the fence; stopping renewal", jobId);
                stop(jobId, leaseToken);
            }
        } catch (RuntimeException e) {
            log.warn("Lease renewal for job {} failed", jobId, e);
        }
    }

    private record Renewal(String leaseToken, ScheduledFuture<?> future) {
    }
}
