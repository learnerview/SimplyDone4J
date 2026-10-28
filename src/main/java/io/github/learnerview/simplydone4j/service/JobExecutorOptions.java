package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.ScheduledExecutorService;

/**
 * The tunables of a {@link JobExecutorService}, collected in one place.
 *
 * <p>A constructor that takes a dozen arguments is a constructor nobody reads: the reader
 * cannot tell which argument is which without looking the call up, and adding a knob means
 * adding another argument and another overload. An options object with a builder keeps
 * every knob named at the call site and lets new ones appear without breaking anybody.</p>
 *
 * <pre>{@code
 * JobExecutorOptions options = JobExecutorOptions.builder()
 *         .timer(timer)
 *         .leaseTimeoutSeconds(60)
 *         .build();
 * }</pre>
 */
@Getter
@Builder
public final class JobExecutorOptions {

    /** Matches the default of {@code simplydone4j.worker.lease-timeout-seconds}. */
    public static final int DEFAULT_LEASE_TIMEOUT_SECONDS = 30;

    /** Matches the default of {@code simplydone4j.executor.default-timeout-seconds}. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * Guards against two jobs with the same unique key running at once. Disabled by
     * default, which costs nothing on the hot path.
     */
    @Builder.Default
    private final UniquenessGuard uniquenessGuard = UniquenessGuard.DISABLED;

    /**
     * How long a job is put back when its unique key is busy. Not a failure, so it does
     * not consume an attempt.
     */
    @Builder.Default
    private final int overlapDeferSeconds = 0;

    /**
     * How long the uniqueness lock is held. Zero means "the job timeout plus a margin".
     */
    @Builder.Default
    private final int uniquenessLockTtlSeconds = 0;

    /** The timeout applied to a job that does not carry its own. */
    @Builder.Default
    private final int defaultTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /**
     * How long a claim stays valid without renewal. The executor renews the lease of
     * every running job at a third of this interval, so a handler that runs longer than
     * the timeout is not reclaimed while it is still working.
     */
    @Builder.Default
    private final int leaseTimeoutSeconds = DEFAULT_LEASE_TIMEOUT_SECONDS;

    /** Where the metrics go. The no-op implementation is used when none is supplied. */
    @Builder.Default
    private final JobMetrics metrics = JobMetrics.NOOP;

    /**
     * The timer that runs the handler watchdog and the lease renewer.
     *
     * <p>Supplied by the caller because its lifetime is the caller's: the library does
     * not shut down a timer it did not create.</p>
     */
    @NonNull
    private final ScheduledExecutorService timer;
}
