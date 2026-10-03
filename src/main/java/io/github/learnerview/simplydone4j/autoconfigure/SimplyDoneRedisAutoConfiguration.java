package io.github.learnerview.simplydone4j.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.HashSet;

@AutoConfiguration(beforeName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
})
@EnableConfigurationProperties(SimplyDoneProperties.class)
@ConditionalOnClass(LettuceConnectionFactory.class)
public final class SimplyDoneRedisAutoConfiguration {

    /**
     * Only contributes a factory when Sentinel or Cluster is actually configured.
     *
     * <p>This class sorts ahead of Boot's Redis auto-configuration so that an explicit
     * Sentinel/Cluster topology wins over Boot's default single-node factory. That
     * ordering is what made the previous shape a bug: the bean used to be declared
     * unconditionally and returned {@code null} for the common case of a plain
     * {@code spring.data.redis.url}. Spring registers a {@code NullBean} for it, which
     * still counts as a {@code RedisConnectionFactory} definition, so Boot's own
     * {@code LettuceConnectionConfiguration} backed off and the application started with
     * no usable connection factory at all. On Spring Boot 3 the
     * {@code beforeName} referenced a class that did not exist, the ordering silently
     * did nothing, and Boot won the race -- so the defect only surfaced on Spring Boot 4.
     *
     * <p>Gating on {@code sentinel-nodes} means the bean is simply absent in the default
     * case, leaving Boot to create its normal factory. Both supported topologies require
     * nodes, so the condition is exactly the set of cases this bean is for.
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    @ConditionalOnProperty(prefix = "simplydone4j.redis", name = "sentinel-nodes")
    public LettuceConnectionFactory simplyDoneRedisConnectionFactory(SimplyDoneProperties props) {
        LettuceConnectionFactory factory = createIfConfigured(props);
        if (factory == null) {
            throw new IllegalStateException(
                    "simplydone4j.redis.sentinel-nodes is set but no usable topology could be built. "
                            + "Set simplydone4j.redis.sentinel-master for Sentinel, or "
                            + "simplydone4j.redis.cluster-mode=true for Cluster.");
        }
        return factory;
    }

    static LettuceConnectionFactory createIfConfigured(SimplyDoneProperties props) {
        SimplyDoneProperties.Redis redis = props.getRedis();
        boolean hasNodes = redis.getSentinelNodes() != null && !redis.getSentinelNodes().isEmpty();
        boolean hasSentinel = hasNodes
                && redis.getSentinelMaster() != null
                && !redis.getSentinelMaster().isBlank();

        if (hasSentinel) {
            RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration(
                    redis.getSentinelMaster(), new HashSet<>(redis.getSentinelNodes()));
            applyPassword(sentinel, redis.getPassword());
            return new LettuceConnectionFactory(sentinel);
        }
        if (hasNodes && redis.isClusterMode()) {
            RedisClusterConfiguration cluster =
                    new RedisClusterConfiguration(redis.getSentinelNodes());
            applyPassword(cluster, redis.getPassword());
            return new LettuceConnectionFactory(cluster);
        }
        return null;
    }

    private static void applyPassword(RedisConfiguration config, String password) {
        if (password != null && !password.isBlank() && RedisConfiguration.isAuthenticationAware(config)) {
            ((RedisConfiguration.WithAuthentication) config).setPassword(RedisPassword.of(password));
        }
    }
}
