package io.github.learnerview.simplydone4j.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
