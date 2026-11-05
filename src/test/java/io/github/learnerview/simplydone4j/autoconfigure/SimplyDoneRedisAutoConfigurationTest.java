package io.github.learnerview.simplydone4j.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SimplyDoneRedisAutoConfigurationTest {

    @Test
    void shouldReturnNullWhenNoHaConfigured() {
        assertNull(SimplyDoneRedisAutoConfiguration.createIfConfigured(new SimplyDoneProperties()));
    }

    @Test
    void shouldReturnNullWhenMasterMissing() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setSentinelNodes(List.of("h1:26379"));
        assertNull(SimplyDoneRedisAutoConfiguration.createIfConfigured(props));
    }

    @Test
    void shouldReturnNullWhenNodesMissing() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setSentinelMaster("mymaster");
        assertNull(SimplyDoneRedisAutoConfiguration.createIfConfigured(props));
    }

    @Test
    void shouldCreateSentinelFactoryWhenMasterAndNodesPresent() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setSentinelMaster("mymaster");
        props.getRedis().setSentinelNodes(List.of("h1:26379", "h2:26379"));
        LettuceConnectionFactory factory = SimplyDoneRedisAutoConfiguration.createIfConfigured(props);
        assertNotNull(factory);
        assertInstanceOf(LettucePoolingClientConfiguration.class, factory.getClientConfiguration());
    }

    @Test
    void shouldCreateClusterFactoryInClusterMode() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setClusterMode(true);
        props.getRedis().setSentinelNodes(List.of("n1:6379", "n2:6379"));
        LettuceConnectionFactory factory = SimplyDoneRedisAutoConfiguration.createIfConfigured(props);
        assertNotNull(factory);
        assertInstanceOf(LettucePoolingClientConfiguration.class, factory.getClientConfiguration());
    }

    @Test
    void shouldApplyConfiguredPoolLimits() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setSentinelMaster("mymaster");
        props.getRedis().setSentinelNodes(List.of("h1:26379"));
        props.getRedis().setPoolMaxTotal(32);
        props.getRedis().setPoolMaxIdle(8);
        props.getRedis().setPoolMinIdle(2);

        LettuceConnectionFactory factory = SimplyDoneRedisAutoConfiguration.createIfConfigured(props);
        LettucePoolingClientConfiguration clientConfig =
                assertInstanceOf(LettucePoolingClientConfiguration.class, factory.getClientConfiguration());

        assertEquals(32, clientConfig.getPoolConfig().getMaxTotal());
        assertEquals(8, clientConfig.getPoolConfig().getMaxIdle());
        assertEquals(2, clientConfig.getPoolConfig().getMinIdle());
    }

    @Test
    void shouldApplyConfiguredCommandTimeout() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setSentinelMaster("mymaster");
        props.getRedis().setSentinelNodes(List.of("h1:26379"));
        props.getRedis().setCommandTimeoutMs(2500L);

        LettuceConnectionFactory factory = SimplyDoneRedisAutoConfiguration.createIfConfigured(props);
        LettucePoolingClientConfiguration clientConfig =
                assertInstanceOf(LettucePoolingClientConfiguration.class, factory.getClientConfiguration());

        assertEquals(Duration.ofMillis(2500L), clientConfig.getCommandTimeout());
    }

    @Test
    void shouldPreferSentinelOverClusterWhenBothConfigured() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setClusterMode(true);
        props.getRedis().setSentinelMaster("mymaster");
        props.getRedis().setSentinelNodes(List.of("h1:26379"));
        assertNotNull(SimplyDoneRedisAutoConfiguration.createIfConfigured(props));
    }
}
