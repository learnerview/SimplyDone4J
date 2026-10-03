package io.github.learnerview.simplydone4j.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the interaction between {@link SimplyDoneRedisAutoConfiguration} and Spring
 * Boot's own Redis auto-configuration.
 *
 * <p>These use a real {@link ApplicationContextRunner} rather than calling
 * {@code createIfConfigured} directly, because the defect they cover was never visible
 * to a direct unit test: the bean was declared unconditionally and returned {@code null}
 * for a plain {@code spring.data.redis.url}. Spring registered a {@code NullBean} for
 * it, which satisfied Boot's {@code @ConditionalOnMissingBean(RedisConnectionFactory.class)},
 * so Boot's {@code LettuceConnectionConfiguration} backed off and the context ended up
 * with no usable connection factory. It reproduced only on Spring Boot 4, where the
 * {@code beforeName} target actually exists; on Boot 3 the ordering silently did nothing
 * and Boot won the race. The 449-test suite missed it because the repository tests mock
 * {@code StringRedisTemplate} and never build this wiring.
 */
class SimplyDoneRedisAutoConfigurationWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SimplyDoneRedisAutoConfiguration.class,
                    // Boot's own Redis auto-configuration is what supplies the
                    // single-node factory in the default case, so it has to be part
                    // of the context for this to model a real application.
                    org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
                    SimplyDoneAutoConfiguration.class))
            .withPropertyValues("spring.data.redis.url=redis://localhost:6379")
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultConfigurationLeavesTheConnectionFactoryToSpringBoot() {
        // No simplydone4j.redis.* set: the starter must not shadow Boot's factory,
        // and the context must still be able to build a StringRedisTemplate.
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedisConnectionFactory.class);
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
        });
    }

    @Test
    void anExplicitlyDefinedFactoryStillWins() {
        contextRunner
                .withBean(RedisConnectionFactory.class,
                        () -> new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                });
    }

    @Test
    void sentinelTopologyContributesTheStarterFactory() {
        contextRunner
                .withPropertyValues(
                        "simplydone4j.redis.sentinel-nodes=h1:26379,h2:26379",
                        "simplydone4j.redis.sentinel-master=mymaster")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                });
    }

    @Test
    void clusterTopologyContributesTheStarterFactory() {
        contextRunner
                .withPropertyValues(
                        "simplydone4j.redis.sentinel-nodes=n1:6379,n2:6379",
                        "simplydone4j.redis.cluster-mode=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RedisConnectionFactory.class);
                });
    }

    @Test
    void nodesWithoutATopologyFailLoudlyRatherThanSilentlyDisablingRedis() {
        contextRunner
                .withPropertyValues("simplydone4j.redis.sentinel-nodes=h1:26379")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("sentinel-master")
                            .hasMessageContaining("cluster-mode");
                });
    }
}
