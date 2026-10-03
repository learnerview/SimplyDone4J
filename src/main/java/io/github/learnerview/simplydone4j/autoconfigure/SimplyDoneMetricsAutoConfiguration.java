package io.github.learnerview.simplydone4j.autoconfigure;

import io.github.learnerview.simplydone4j.metrics.JobMetrics;
import io.github.learnerview.simplydone4j.metrics.MicrometerJobMetrics;
import io.github.learnerview.simplydone4j.metrics.QueueDepthSampler;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer-backed instrumentation, kept out of
 * {@link SimplyDoneAutoConfiguration} for the same reason as
 * {@link SimplyDoneHealthAutoConfiguration}: {@code micrometer-core} is an
 * {@code optional} dependency, and a bean signature that mentions
 * {@link MeterRegistry} forces Spring to resolve that type while introspecting
 * the owning class. Hosts without Micrometer would then fail at startup with
 * {@code TypeNotPresentException} instead of silently getting no-op metrics.
 *
 * <p>Runs {@code before} the core configuration so its {@link JobMetrics} wins
 * over the no-op fallback declared there; the ordering plus
 * {@code @ConditionalOnMissingBean} on both sides keeps exactly one bean of the
 * type either way.
 */
@AutoConfiguration(before = SimplyDoneAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
public class SimplyDoneMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.metrics", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public JobMetrics jobMetrics(ObjectProvider<MeterRegistry> registryProvider, SimplyDoneProperties props) {
        if (!props.getMetrics().isEnabled()) {
            return JobMetrics.NOOP;
        }
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? JobMetrics.NOOP : new MicrometerJobMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "simplydone4j.metrics", name = "queue-depth",
            havingValue = "true", matchIfMissing = true)
    public QueueDepthSampler queueDepthSampler(QueueRepository queueRepo, JobRepository jobRepo,
                                               ObjectProvider<MeterRegistry> registryProvider) {
        return new QueueDepthSampler(queueRepo, jobRepo, registryProvider.getIfAvailable());
    }
}
