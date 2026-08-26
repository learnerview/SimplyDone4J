package io.github.learnerview.simplydone4j.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

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
    }

    @Test
    void shouldCreateClusterFactoryInClusterMode() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRedis().setClusterMode(true);
        props.getRedis().setSentinelNodes(List.of("n1:6379", "n2:6379"));
        LettuceConnectionFactory factory = SimplyDoneRedisAutoConfiguration.createIfConfigured(props);
        assertNotNull(factory);
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
