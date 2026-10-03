package io.github.learnerview.simplydone4j.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
    private final RateLimit rateLimit = new RateLimit();
    private final Retry retry = new Retry();
    private final Worker worker = new Worker();
    private final Queue queue = new Queue();
    private final Executor executor = new Executor();
    private final Monitoring monitoring = new Monitoring();
    private final Metrics metrics = new Metrics();
    private final Health health = new Health();
    private final Webhook webhook = new Webhook();
    private final Uniqueness uniqueness = new Uniqueness();
    private final Retention retention = new Retention();
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

        private long pollingIntervalMs = 1000L;

        /**
         * Explicit override for the queue key prefix. Leave blank to derive it from
         * {@code keyPrefix}: a separate default here meant that changing
         * {@code key-prefix} left the queues behind under the old namespace.
         */
        private String queuePrefix;

        /**
         * Jobs taken per poll, bounded by worker capacity. Raising it is the throughput
         * lever; the old ceiling of {@code 1000 / pollingIntervalMs} no longer applies now
         * that claims are batched.
         */
        private int batchSize = 10;

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
    public static final class RateLimit {
        /**
         * Ceiling on submissions per producer per {@link #windowSeconds}. Enforced as a
         * token bucket.
         */
        private int requestsPerMinute = 60;

        private int windowSeconds = 60;

        /**
         * Consecutive failures of the limiter backend before the breaker opens and the
         * in-memory strategy takes over.
         *
         * <p>Failing open is a deliberate trade: admitting slightly too many jobs is a
         * better outcome than refusing every submission because Redis blipped.</p>
         */
        private int circuitBreakerFailures = 5;

        private long circuitBreakerResetSeconds = 30L;

        /**
         * Reserved for API compatibility. The current circuit-breaker implementation
         * counts infrastructure failures only and does not yet inspect handler duration,
         * so this value is accepted but not enforced.
         */
        private long slowCallDurationMs = 2000L;
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
         */
        private long maxDepth = 10000L;
    }

    @Data
    public static final class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
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
     * Callback delivery. Best effort by design: the terminal job state is persisted
     * before a webhook is attempted, so a delivery failure is logged and retried in the
     * background rather than allowed to alter the job's outcome.
     */
    @Data
    public static final class Webhook {
        /**
         * On by default, and safe to leave on: nothing is sent unless a job carries a
         * {@code callbackUrl}, and a job only carries one if the caller set it. Turning
         * this off suppresses every callback, which is the switch to reach for when a
         * shared deployment wants a job type's callbacks disabled wholesale.
         */
        private boolean enabled = true;
        private int connectTimeoutMillis = 2000;
        private int requestTimeoutMillis = 5000;

        /** Turning this off collapses every delivery to a single attempt. */
        private boolean retryEnabled = true;

        /** Total attempts including the first. */
        private int maxAttempts = 3;

        private long initialDelayMillis = 500L;
        private double backoffMultiplier = 2.0;
        private long maxDelayMillis = 30000L;
        private double jitterFactor = 0.2;
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
    }

    /**
     * Redis connection shape. The starter uses Spring Boot's own
     * {@code spring.data.redis.*} properties for the connection itself; these exist for
     * the Sentinel and cluster topologies the starter can build a factory for.
     */
    @Data
    public static final class Redis {
        private boolean enabled = true;
        private String sentinelMaster;
        private List<String> sentinelNodes;
        private boolean clusterMode;
        private String password;
    }
}
