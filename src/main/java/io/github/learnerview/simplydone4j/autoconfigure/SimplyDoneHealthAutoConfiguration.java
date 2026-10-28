package io.github.learnerview.simplydone4j.autoconfigure;

import io.github.learnerview.simplydone4j.health.SimplyDoneHealthIndicator;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/**
 * Health-indicator wiring, deliberately kept out of {@link SimplyDoneAutoConfiguration}.
 *
 * <p>{@code org.springframework.boot.health.contributor} is a Spring Boot 4
 * package; on Boot 3 the equivalent types live in
 * {@code org.springframework.boot.actuate.health}. A {@code @Bean} method whose
 * declared return type is a Boot 4-only class forces Spring to resolve that type
 * while introspecting the owning configuration class, so declaring this bean on
 * {@code SimplyDoneAutoConfiguration} made every Spring Boot 3.x consumer fail at
 * startup with {@code NoClassDefFoundError: .../boot/health/contributor/HealthIndicator}
 * — even for users who never enabled health checks. A class-level
 * {@code @ConditionalOnClass} is evaluated from annotation metadata before the
 * class is loaded, so on Boot 3 this configuration is filtered out and the
 * Boot 4-only types are never touched.
 */
@AutoConfiguration(after = SimplyDoneAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
@ConditionalOnProperty(prefix = "simplydone4j.health", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SimplyDoneHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HealthIndicator simplyDoneHealthIndicator(QueueRepository queueRepo, JobRepository jobRepo,
                                                    SimplyDoneProperties props) {
        return new SimplyDoneHealthIndicator(queueRepo, jobRepo,
                props.getHealth().getDeadLetterThreshold(), props.getHealth().getMaxQueueDepth());
    }
}
