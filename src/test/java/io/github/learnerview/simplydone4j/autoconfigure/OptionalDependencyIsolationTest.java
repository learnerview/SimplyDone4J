package io.github.learnerview.simplydone4j.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the optional-dependency contract of the starter.
 *
 * <p>Both {@code spring-boot-starter-actuator} and {@code micrometer-core} are
 * declared {@code optional}, so the published jar must be usable on a Spring Boot
 * 3.x host that pulls in neither. That only holds while the core
 * {@link SimplyDoneAutoConfiguration} mentions no type from those libraries in a
 * bean signature: Spring resolves a {@code @Bean} method's declared return type
 * and its parameter types while introspecting the owning class, so a
 * {@code HealthIndicator} or {@code ObjectProvider<MeterRegistry>} there breaks
 * the whole context with {@code NoClassDefFoundError} /
 * {@code TypeNotPresentException} before any property is even consulted.
 *
 * <p>This is a compile-time-visible hazard, so it is asserted reflectively over
 * the current signatures rather than by booting a second Spring Boot version.
 */
class OptionalDependencyIsolationTest {

    private static final List<String> OPTIONAL_TYPE_PREFIXES = List.of(
            "org.springframework.boot.health",        // Boot 4 only; Boot 3 uses actuator.health
            "org.springframework.boot.actuate",
            "io.micrometer");

    @Test
    void coreAutoConfigurationMustNotReferenceOptionalDependencyTypes() {
        List<String> violations = new ArrayList<>();

        for (Method method : SimplyDoneAutoConfiguration.class.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, Bean.class)) {
                continue;
            }
            for (String type : referencedTypeNames(method)) {
                if (isOptionalDependencyType(type)) {
                    violations.add(method.getName() + "() references " + type);
                }
            }
        }

        assertThat(violations)
                .as("Optional-dependency types must stay out of SimplyDoneAutoConfiguration's "
                        + "bean signatures; put them in a @ConditionalOnClass-gated configuration")
                .isEmpty();
    }

    @Test
    void optionalDependencyBehavioursLiveInTheirOwnConfigurations() {
        // Guards the structural half of the contract: the health and metrics beans
        // are expected to be declared on the dedicated configurations, so a future
        // refactor cannot quietly move them back onto the core class.
        assertThat(beanMethodNames(SimplyDoneHealthAutoConfiguration.class))
                .contains("simplyDoneHealthIndicator");
        assertThat(beanMethodNames(SimplyDoneMetricsAutoConfiguration.class))
                .contains("jobMetrics", "queueDepthSampler");
    }

    private static List<String> referencedTypeNames(Method method) {
        List<String> names = new ArrayList<>();
        names.add(method.getReturnType().getTypeName());
        for (java.lang.reflect.Parameter parameter : method.getParameters()) {
            // The rendered generic type keeps ObjectProvider<MeterRegistry> visible.
            names.add(parameter.getParameterizedType().getTypeName());
        }
        return names;
    }

    private static boolean isOptionalDependencyType(String typeName) {
        return OPTIONAL_TYPE_PREFIXES.stream().anyMatch(typeName::contains);
    }

    private static List<String> beanMethodNames(Class<?> configurationClass) {
        return Arrays.stream(configurationClass.getDeclaredMethods())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, Bean.class))
                .map(Method::getName)
                .toList();
    }
}
