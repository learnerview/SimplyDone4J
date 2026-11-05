package io.github.learnerview.simplydone4j.autoconfigure;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;
import java.util.HashSet;

@AutoConfiguration(beforeName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
})
@EnableConfigurationProperties(SimplyDoneProperties.class)
@ConditionalOnClass({LettuceConnectionFactory.class, GenericObjectPoolConfig.class})
@Conditional(SimplyDoneRedisAutoConfiguration.HaRedisCondition.class)
public final class SimplyDoneRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory simplyDoneRedisConnectionFactory(SimplyDoneProperties props) {
        return createIfConfigured(props);
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
            return new LettuceConnectionFactory(sentinel, pooledClientConfiguration(redis));
        }
        if (hasNodes && redis.isClusterMode()) {
            RedisClusterConfiguration cluster =
                    new RedisClusterConfiguration(redis.getSentinelNodes());
            applyPassword(cluster, redis.getPassword());
            return new LettuceConnectionFactory(cluster, pooledClientConfiguration(redis));
        }
        return null;
    }

    /**
     * WATCH/MULTI uses a dedicated Lettuce connection per transaction. Without pooling,
     * every scheduler claim and lease write opens a new TCP connection, which exhausts the
     * client's ephemeral port range under sustained load. Pooling reuses those dedicated
     * connections instead of burning a port per operation.
     */
    private static LettucePoolingClientConfiguration pooledClientConfiguration(
            SimplyDoneProperties.Redis redis) {
        int maxTotal = Math.max(1, redis.getPoolMaxTotal());
        int maxIdle = Math.min(maxTotal, Math.max(0, redis.getPoolMaxIdle()));
        int minIdle = Math.min(maxIdle, Math.max(0, redis.getPoolMinIdle()));
        GenericObjectPoolConfig<StatefulConnection<?, ?>> pool = new GenericObjectPoolConfig<>();
        pool.setMaxTotal(maxTotal);
        pool.setMaxIdle(maxIdle);
        pool.setMinIdle(minIdle);
        pool.setBlockWhenExhausted(true);
        pool.setMaxWait(Duration.ofMillis(Math.max(1L, redis.getPoolMaxWaitMs())));
        return LettucePoolingClientConfiguration.builder()
                .poolConfig(pool)
                .commandTimeout(Duration.ofMillis(Math.max(1L, redis.getCommandTimeoutMs())))
                .build();
    }

    private static void applyPassword(RedisConfiguration config, String password) {
        if (password != null && !password.isBlank() && RedisConfiguration.isAuthenticationAware(config)) {
            ((RedisConfiguration.WithAuthentication) config).setPassword(RedisPassword.of(password));
        }
    }

    public static final class HaRedisCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            SimplyDoneProperties.Redis redis;
            try {
                redis = Binder.get(context.getEnvironment())
                        .bind("simplydone4j.redis", Bindable.of(SimplyDoneProperties.Redis.class))
                        .orElse(null);
            } catch (RuntimeException e) {
                return false;
            }
            if (redis == null) {
                return false;
            }
            boolean hasNodes = redis.getSentinelNodes() != null && !redis.getSentinelNodes().isEmpty();
            boolean hasSentinelMaster = hasText(redis.getSentinelMaster());
            return hasNodes && (hasSentinelMaster || redis.isClusterMode());
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
