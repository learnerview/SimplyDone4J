package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published {@code additional-spring-configuration-metadata.json} overrides the
 * defaults the annotation processor infers from {@link SimplyDoneProperties}, and those
 * overrides are exactly what an IDE shows a user. When they disagree with the field
 * initialisers the documentation is actively wrong.
 *
 * <p>Two had drifted: {@code ttl-days} advertised {@code 30} while the field defaults to
 * {@code 0} — so real retention was 1 hour, not 30 days — and
 * {@code scheduler.queue-prefix} still advertised the hard-coded
 * {@code simplydone4j:queue} after the prefix was changed to derive from
 * {@code key-prefix}. Neither is visible in the running code, which is why this compares
 * the declared value against the field rather than trusting either one.
 */
class ConfigurationMetadataDefaultsTest {

    private static final String METADATA = "META-INF/additional-spring-configuration-metadata.json";

    /** Metadata value for every property the file declares, keyed by property name. */
    private Map<String, Object> declaredDefaults() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(METADATA)) {
            assertThat(in).as("%s must be on the classpath", METADATA).isNotNull();
            Map<String, Object> out = new LinkedHashMap<>();
            for (JsonNode property : new ObjectMapper().readTree(in).path("properties")) {
                if (property.has("defaultValue")) {
                    out.put(property.get("name").asText(), scalar(property.get("defaultValue")));
                }
            }
            return out;
        }
    }

    /** The value a fresh, unconfigured instance actually has. */
    private Map<String, Object> actualDefaults() {
        SimplyDoneProperties p = new SimplyDoneProperties();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simplydone4j.scheduler.enabled", p.getScheduler().isEnabled());
        out.put("simplydone4j.scheduler.polling-interval-ms", p.getScheduler().getPollingIntervalMs());
        out.put("simplydone4j.scheduler.weights.high", p.getScheduler().getWeights().getHigh());
        out.put("simplydone4j.scheduler.weights.normal", p.getScheduler().getWeights().getNormal());
        out.put("simplydone4j.scheduler.weights.low", p.getScheduler().getWeights().getLow());
        out.put("simplydone4j.rate-limit.requests-per-minute", p.getRateLimit().getRequestsPerMinute());
        out.put("simplydone4j.rate-limit.window-seconds", p.getRateLimit().getWindowSeconds());
        out.put("simplydone4j.retry.max-attempts", p.getRetry().getMaxAttempts());
        out.put("simplydone4j.retry.initial-delay-seconds", p.getRetry().getInitialDelaySeconds());
        out.put("simplydone4j.retry.backoff-multiplier", p.getRetry().getBackoffMultiplier());
        out.put("simplydone4j.worker.lease-timeout-seconds", p.getWorker().getLeaseTimeoutSeconds());
        out.put("simplydone4j.worker.retry-promoter-interval-ms", p.getWorker().getRetryPromoterIntervalMs());
        out.put("simplydone4j.worker.lease-reaper-interval-ms", p.getWorker().getLeaseReaperIntervalMs());
        out.put("simplydone4j.queue.max-depth", p.getQueue().getMaxDepth());
        out.put("simplydone4j.executor.core-pool-size", p.getExecutor().getCorePoolSize());
        out.put("simplydone4j.executor.max-pool-size", p.getExecutor().getMaxPoolSize());
        out.put("simplydone4j.executor.queue-capacity", p.getExecutor().getQueueCapacity());
        out.put("simplydone4j.executor.keep-alive-seconds", p.getExecutor().getKeepAliveSeconds());
        out.put("simplydone4j.executor.default-timeout-seconds", p.getExecutor().getDefaultTimeoutSeconds());
        out.put("simplydone4j.executor.await-termination-seconds", p.getExecutor().getAwaitTerminationSeconds());
        out.put("simplydone4j.key-prefix", p.getKeyPrefix());
        out.put("simplydone4j.ttl-days", p.getTtlDays());
        out.put("simplydone4j.idempotency-ttl-hours", p.getIdempotencyTtlHours());
        out.put("simplydone4j.monitoring.enabled", p.getMonitoring().isEnabled());
        out.put("simplydone4j.metrics.enabled", p.getMetrics().isEnabled());
        out.put("simplydone4j.metrics.queue-depth", Boolean.TRUE); // gated by @ConditionalOnProperty(matchIfMissing = true)
        out.put("simplydone4j.metrics.queue-depth-refresh-seconds", p.getMetrics().getQueueDepthRefreshSeconds());
        out.put("simplydone4j.health.dead-letter-threshold", (long) p.getHealth().getDeadLetterThreshold());
        out.put("simplydone4j.health.max-queue-depth", p.getHealth().getMaxQueueDepth());
        out.put("simplydone4j.retention.store-execution-logs", p.getRetention().isStoreExecutionLogs());
        out.put("simplydone4j.rate-limit.slow-call-duration-ms", p.getRateLimit().getSlowCallDurationMs());
        out.put("simplydone4j.rate-limit.circuit-breaker-failures", p.getRateLimit().getCircuitBreakerFailures());
        out.put("simplydone4j.rate-limit.circuit-breaker-reset-seconds", p.getRateLimit().getCircuitBreakerResetSeconds());
        return out;
    }

    @Test
    void everyDeclaredDefaultMatchesTheRealDefault() throws Exception {
        Map<String, Object> declared = declaredDefaults();
        Map<String, Object> actual = actualDefaults();

        Map<String, String> mismatches = new LinkedHashMap<>();
        declared.forEach((name, stated) -> {
            Object real = actual.get(name);
            if (real == null) {
                return;
            }
            if (!String.valueOf(stated).equals(String.valueOf(real))) {
                mismatches.put(name, "metadata says " + stated + " but the default is " + real);
            }
        });

        assertThat(mismatches)
                .as("additional-spring-configuration-metadata.json must not contradict the code")
                .isEmpty();
    }

    @Test
    void everyDeclaredDefaultIsCoveredByAnActualLookup() throws Exception {
        assertThat(actualDefaults().keySet())
                .as("a declared default with no counterpart here is untested; add it above")
                .containsAll(declaredDefaults().keySet());
    }

    @Test
    void ttlDaysIsZeroSoDefaultRetentionIsOneHour() {
        SimplyDoneProperties p = new SimplyDoneProperties();
        assertThat(p.getTtlDays()).isZero();
        assertThat(p.getTtlHours()).isEqualTo(1);
    }

    @Test
    void queuePrefixIsUnsetSoItDerivesFromKeyPrefix() {
        String queuePrefix = new SimplyDoneProperties().getScheduler().getQueuePrefix();
        assertThat(queuePrefix == null || queuePrefix.isBlank())
                .as("unset means RedisQueueRepository derives '<key-prefix>:queue', "
                        + "but was '%s'", queuePrefix)
                .isTrue();
    }

    @Test
    void executionLogsAreOffByDefault() {
        assertThat(new SimplyDoneProperties().getRetention().isStoreExecutionLogs()).isFalse();
    }

    private static Object scalar(JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
