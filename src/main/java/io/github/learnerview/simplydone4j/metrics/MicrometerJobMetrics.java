package io.github.learnerview.simplydone4j.metrics;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed {@link JobMetrics}.
 *
 * <p>All meters are resolved once and cached. Calling {@code registry.counter(...)} on
 * the hot path would re-look-up a meter by name on every event, which is a hash lookup
 * plus a map probe per job; the caches below turn that into a single concurrent read.</p>
 */
public final class MicrometerJobMetrics implements JobMetrics {

    private final Counter submitted;
    private final Counter claimed;
    private final Counter fencedRejections;
    private final Counter leasesReaped;
    private final Counter overlapped;
    private final Timer pollTimer;
    private final DistributionSummary claimsPerPoll;
    private final ConcurrentMap<JobPriority, Counter> submittedByPriority = new ConcurrentHashMap<>();
    private final ConcurrentMap<Outcome, Counter> completedByOutcome = new ConcurrentHashMap<>();
    private final ConcurrentMap<Outcome, Timer> durationByOutcome = new ConcurrentHashMap<>();

    public MicrometerJobMetrics(MeterRegistry registry) {
                this.submitted = Counter.builder("simplydone4j.jobs.submitted")
                .description("Jobs accepted by the submission service")
                .register(registry);
        this.claimed = Counter.builder("simplydone4j.jobs.claimed")
                .description("Jobs taken off the priority queues by a worker")
                .register(registry);
        this.fencedRejections = Counter.builder("simplydone4j.fencing.rejections")
                .description("Terminal writes rejected because the lease was no longer held")
                .register(registry);
        this.leasesReaped = Counter.builder("simplydone4j.lease.reaped")
                .description("Expired leases recovered by the reaper")
                .register(registry);
        this.overlapped = Counter.builder("simplydone4j.jobs.overlapped")
                .description("Executions deferred because the job's unique key was already in use")
                .register(registry);
        this.pollTimer = Timer.builder("simplydone4j.scheduler.poll")
                .description("Time spent in one scheduler poll")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.claimsPerPoll = DistributionSummary.builder("simplydone4j.scheduler.claims.per.poll")
                .description("Jobs claimed by each scheduler poll")
                .register(registry);

        for (JobPriority priority : JobPriority.values()) {
            submittedByPriority.put(priority, Counter.builder("simplydone4j.jobs.submitted.by.priority")
                    .description("Jobs accepted by the submission service, by priority")
                    .tag("priority", priority.name())
                    .register(registry));
        }
        for (Outcome outcome : Outcome.values()) {
            completedByOutcome.put(outcome, Counter.builder("simplydone4j.jobs.completed")
                    .description("Terminal job outcomes")
                    .tag("outcome", outcome.name())
                    .register(registry));
            durationByOutcome.put(outcome, Timer.builder("simplydone4j.jobs.duration")
                    .description("Wall time from worker start to terminal write")
                    .tag("outcome", outcome.name())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry));
        }
    }

    @Override
    public void recordSubmitted(JobPriority priority) {
        submitted.increment();
        Counter byPriority = submittedByPriority.get(priority);
        if (byPriority != null) byPriority.increment();
    }

    @Override
    public void recordClaimed(int count) {
        if (count > 0) claimed.increment(count);
    }

    @Override
    public void recordCompleted(Outcome outcome, long durationMs) {
        Counter counter = completedByOutcome.get(outcome);
        if (counter != null) counter.increment();
        Timer timer = durationByOutcome.get(outcome);
        if (timer != null) timer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordFencedWriteRejected() {
        fencedRejections.increment();
    }

    @Override
    public void recordPoll(long durationMs, int claimedCount) {
        pollTimer.record(Math.max(0L, durationMs), TimeUnit.MILLISECONDS);
        // A distribution rather than a tagged counter: the claim count per poll is
        // unbounded, so tagging on it would blow the metric cardinality budget.
        claimsPerPoll.record(Math.max(0, claimedCount));
    }

    @Override
    public void recordLeaseReaped() {
        leasesReaped.increment();
    }

    @Override
    public void recordOverlapped() {
        overlapped.increment();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
