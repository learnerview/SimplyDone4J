package io.github.learnerview.simplydone4j.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable in the starter, bound from the {@code simplydone4j} prefix.
 *
 * <p>All fields have working defaults, so the starter runs with no configuration at all.
 * The defaults suit a single node; the ones that matter once you scale out are called
 * out on the individual fields.</p>
 *
 * <p>Nested types rather than a flat namespace because the property names mirror the
 * structure: {@code simplydone4j.retry.max-attempts} binds to
 * {@code Retry.maxAttempts}. The nesting is also what makes the generated configuration
 * metadata readable, since the annotation processor walks it.</p>
 *
 * <p>The nested holders are final and initialised inline, so binding only ever reaches
 * them through their setters. That is what stops a malformed value from replacing a whole
 * sub-tree with a fresh object partway through startup.</p>
 */
@Data
@ConfigurationProperties(prefix = "simplydone4j")
public class SimplyDoneProperties {

    private final Scheduler scheduler = new Scheduler();
    private final Retry retry = new Retry();
    private final Worker worker = new Worker();
    private final Queue queue = new Queue();
    private final Executor executor = new Executor();
    private final Monitoring monitoring = new Monitoring();
    private final Metrics metrics = new Metrics();
    private final Health health = new Health();
    private final Uniqueness uniqueness = new Uniqueness();
    private final Retention retention = new Retention();
    private final Fencing fencing = new Fencing();
    private final Redis redis = new Redis();

    /**
     * Retention for a job that reached a terminal state: this many whole days, plus
     * {@link #ttlHours}. Separate from the delay before a job becomes visible, because
     * the two answer different questions: when may this run, and when may I forget it.
     */
    private int ttlDays = 0;

    /** How long an idempotency key is remembered. Set it above the longest client retry window. */
    private int idempotencyTtlHours = 1;

    /** Hour component of terminal-job retention. */
    private int ttlHours = 1;

    /**
     * Namespace for every Redis key the starter writes.
     *
     * <p>Change this to run two logically distinct deployments against one Redis
     * instance, such as staging and production on a shared cluster. Everything derives
     * from it, including the priority queues and the uniqueness locks, so setting it once
     * isolates the whole footprint instead of leaving stray un-namespaced keys behind.</p>
     */
    private String keyPrefix = "simplydone4j";

    @Data
    public static final class Scheduler {
        /**
         * Declared so the property is bindable and appears in the generated metadata.
         * The scheduler bean is gated on it in {@code SimplyDoneAutoConfiguration}.
         */
        private boolean enabled = true;

        /**
         * How long the scheduler waits when there is nothing to do.
         *
         * <p>This is the idle interval, not the interval between polls under load: a poll
         * that finds work comes back quickly, so a busy queue is not held back by the idle
         * interval.</p>
         */
        private long pollingIntervalMs = 1000L;

        /**
         * How long the scheduler waits after a poll that found work.
         *
         * <p>Short on purpose. A worker that finishes a job should get the next one
         * immediately, not after the idle interval, and the poll itself is cheap when
         * there is nothing to claim.</p>
         */
        private long busyPollingIntervalMs = 50L;

        /**
         * Explicit override for the queue key prefix. Leave blank to derive it from
         * {@code keyPrefix}: a separate default here meant that changing
         * {@code key-prefix} left the queues behind under the old namespace.
         */
        private String queuePrefix;

        /**
         * Jobs taken per poll.
         *
         * <p>{@code 0} means "as many as the workers can start", which is what keeps the
         * pipeline from idling while work is queued. The scheduler never claims more than
         * the pool has free slots for, so a burst does not turn into a thousand queued
         * tasks and a thousand rejected submissions.</p>
         */
        private int batchSize = 0;

        /**
         * Threads in the library's own timer pool.
         *
         * <p>The poll, the retry promoter, the lease reaper, the queue-depth sampler and
         * the per-job watchdog all run on it. More than one thread is deliberate: a poll
         * blocked on a slow Redis must not delay a watchdog that is about to record a
         * timeout, nor a lease renewal that keeps a running job from being reclaimed.</p>
         */
        private int timerPoolSize = 4;

        private final Weights weights = new Weights();
    }

    /**
     * Deficit round-robin weights across the priority queues.
     *
     * <p>Relative, not percentages, and need not sum to anything. Each poll adds these
     * credits to the matching queue and the queue holding the most unspent credit is
     * served next. A queue that stays empty forfeits its credit, which is what stops an
     * idle high-priority queue from hoarding an unbounded advantage.</p>
     */
    @Data
    public static final class Weights {
        private int high = 70;
        private int normal = 20;
        private int low = 10;
    }

    @Data
    public static final class Retry {
        /**
         * Attempt budget for a job that specifies none. Exhausting it is what moves a
         * job to {@code DLQ}, so too low discards recoverable work while too high keeps a
         * permanently broken job circulating.
         */
        private int maxAttempts = 3;

        private long initialDelaySeconds = 5L;

        private double backoffMultiplier = 2.0;

        /** Upper bound on a single backoff, applied after exponential growth. */
        private long maxDelaySeconds = 300L;

        /**
         * Fraction of the computed delay randomised, in {@code [0, 1]}. Without it every
         * job that failed against the same outage retries in lockstep and reproduces the
         * outage on recovery.
         */
        private double jitterFactor = 0.2;
    }

    @Data
    public static final class Worker {
        /**
         * How long a claim is valid. A handler running longer than this has its lease
         * expire and the job reaped and re-run elsewhere; the fencing token is what stops
         * the original worker from overwriting the newer attempt.
         */
        private int leaseTimeoutSeconds = 30;

        private long retryPromoterIntervalMs = 1000L;

        private long leaseReaperIntervalMs = 5000L;
    }

    @Data
    public static final class Queue {
        /**
         * Depth at which submission is refused with {@code QueueFullException}. A
         * backpressure valve: at this point the workers cannot keep up, and accepting
         * more work only deepens the backlog.
         *
         * <p>The cap is the <em>total</em> across all priority queues, not the depth of
         * each one: a submission is refused once {@code HIGH}, {@code NORMAL} and
         * {@code LOW} together hold this many jobs. The check and the write happen in one
         * Redis script, so concurrent submissions cannot both slip past it.</p>
         */
        private long maxDepth = 10000L;
    }

    @Data
    public static final class Executor {
        /**
         * Threads that run handlers.
         *
         * <p>{@code 0} means "derive it from the machine": the number of available
         * processors, at least two and at most eight. A handler is usually IO bound, so
         * more threads than cores is reasonable, but an unbounded number is not: it turns
         * a burst of submissions into a burst of database connections, and every thread
         * holds a Redis connection while it works.</p>
         */
        private int corePoolSize = 0;

        /**
         * Upper bound on handler threads. {@code 0} means the same as the core size.
         *
         * <p>Kept equal to the core size by default on purpose: a {@code ThreadPoolExecutor}
         * with a bounded queue only grows past its core size once that queue is full, so a
         * larger maximum would sit unused behind a hundred queued tasks.</p>
         */
        private int maxPoolSize = 0;

        /**
         * How many claimed jobs may wait for a free thread.
         *
         * <p>The scheduler only claims what the pool can start, so this queue stays empty
         * in normal operation. It exists to absorb the moment when a handler finishes and
         * the next one has not been dispatched yet.</p>
         */
        private int queueCapacity = 100;

        private int keepAliveSeconds = 60;

        /** Applied to a job that does not carry its own timeout. */
        private int defaultTimeoutSeconds = 30;

        /** How long shutdown waits for in-flight handlers before forcing termination. */
        private int awaitTerminationSeconds = 30;
    }

    @Data
    public static final class Monitoring {
        /**
         * Declared so the property is bindable and appears in the generated metadata.
         * The monitoring bean is gated on it in {@code SimplyDoneAutoConfiguration}.
         */
        private boolean enabled = true;
    }

    /**
     * Micrometer instrumentation. Off by default so a consumer who never adds
     * {@code micrometer-core} does not hit a hard failure at startup; the no-op
     * implementation keeps the engine running either way.
     */
    @Data
    public static final class Metrics {
        private boolean enabled = true;

        /**
         * How often queue-depth gauges are refreshed. Sampled off the scrape path.
         */
        private long queueDepthRefreshSeconds = 30;
    }

    /**
     * Actuator health contributions.
     *
     * <p>A threshold of {@code 0} disables the corresponding check, which is how to get
     * repository-only health without also alerting on a busy queue or a non-empty DLQ.</p>
     */
    @Data
    public static final class Health {
        private boolean enabled = true;

        /** DLQ count at or above which health reports DOWN. {@code 0} disables the check. */
        private int deadLetterThreshold = 1;

        /**
         * Total depth across all priority queues above which health reports DOWN.
         * {@code 0} disables the check.
         */
        private long maxQueueDepth = 100000L;
    }

    /**
     * Cluster-wide mutual exclusion for jobs that submit a {@code uniqueKey}.
     *
     * <p>Off by default: it costs one Redis round trip per keyed job, and most work has
     * no ordering requirement worth paying for. Turn it on when a job type genuinely must
     * not overlap with itself.</p>
     */
    @Data
    public static final class Uniqueness {
        private boolean enabled = false;

        /**
         * How long to hold the key. Should exceed the job's execution timeout so a slow
         * run keeps its lock until it really finishes; if it lapses early a second job
         * can start while the first is still going.
         */
        private int ttlSeconds = 0;

        /**
         * How long to postpone a job that found its key already held. Long enough to let
         * the running job make progress, short enough that a brief overlap clears before
         * the next scheduling tick.
         */
        private int deferSeconds = 5;
    }

    @Data
    public static final class Retention {
        /**
         * Drop the payload once a job is terminal. Trades auditability for footprint on
         * large queues; the result and error text are kept either way.
         */
        private boolean clearPayloadOnCompletion = false;

        /**
         * Keep a per-attempt execution log. Costs one Redis list entry per attempt,
         * trimmed to {@link #maxExecutionLogsPerJob} per job.
         *
         * <p>Off by default. The log is a debugging aid, not something the engine needs
         * to run a job, and Redis is frequently a shared or metered instance. Anything
         * that only exists for diagnostics should be opted into rather than paid for by
         * every deployment; turn it on when investigating retries, timeouts or a stuck
         * job, and turn it back off afterwards.
         */
        private boolean storeExecutionLogs = false;

        private int maxExecutionLogsPerJob = 50;

        /**
         * How often terminal status-index members outside the retention window are
         * removed. Terminal indexes are shared ZSETs, so they are pruned by score rather
         * than by a per-key TTL.
         */
        private long sweepIntervalMs = 3600000L;
    }

    /**
     * How fencing tokens are made durable across a failover.
     *
     * <p>A fencing token has to be unique across every epoch of ownership, including the
     * epochs that straddle a Sentinel promotion. A plain {@code INCR} on the old master
     * can vanish in the promotion, and the new master can hand out the same number again.
     * Waiting for a replica to acknowledge the increment narrows that window: the
     * acknowledged write is in the replica's replication stream, so a promotion of that
     * replica keeps it.</p>
     */
    @Data
    public static final class Fencing {
        /**
         * How many replicas must acknowledge a fencing-token increment before the token is
         * handed out.
         *
         * <p>Defaults to {@code 0}, because {@code WAIT} on a single-node Redis has no
         * replica to acknowledge it and blocks until the timeout. Set this to {@code 1}
         * (or the number of replicas whose promotion must preserve the sequence) when the
         * deployment uses Sentinel or a replica set.</p>
         */
        private int waitForReplicas = 0;

        /**
         * How long the acknowledgement may take, in milliseconds. The wait ends as soon as
         * the replicas have acknowledged, so this is an upper bound on how long a claim can
         * be delayed by an unhealthy replica set, not a fixed cost.
         */
        private long waitTimeoutMs = 500L;
    }

    /**
     * Redis connection shape. The starter uses Spring Boot's own
     * {@code spring.data.redis.*} properties for the connection itself; these exist for
     * the Sentinel topology the starter can build a factory for.
     *
     * <p>Redis Cluster is not supported: the atomic write is one script over the job hash
     * and thirty index keys, and a Cluster only runs a script when all of its keys share
     * a hash slot.</p>
     */
    @Data
    public static final class Redis {
        private String sentinelMaster;
        private List<String> sentinelNodes;
        private String password;
    }
}
