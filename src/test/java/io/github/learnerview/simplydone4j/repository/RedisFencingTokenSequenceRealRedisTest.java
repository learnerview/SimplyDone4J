package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fencing counter must be monotonic and must never expire.
 *
 * <p>Both properties are load-bearing. A counter that expired would restart at zero, and
 * a token that was already handed out would come back -- which is exactly the write from a
 * superseded worker that the fence exists to reject. The expiry check therefore looks at
 * the key itself rather than at the token values.</p>
 */
class RedisFencingTokenSequenceRealRedisTest {

    private static final String HOST = System.getProperty("it.redis.host",
            System.getenv().getOrDefault("IT_REDIS_HOST", "localhost"));
    private static final int PORT = Integer.parseInt(System.getProperty("it.redis.port",
            System.getenv().getOrDefault("IT_REDIS_PORT", "16379")));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SimplyDoneProperties props;

    @BeforeAll
    static void connectToRedis() {
        boolean reachable;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(HOST, PORT));
            factory.afterPropertiesSet();
            factory.getConnection().ping();
            connectionFactory = factory;
            reachable = true;
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "Needs a real Redis at " + HOST + ":" + PORT
                        + " (set -Dit.redis.host/-Dit.redis.port).");
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        props = new SimplyDoneProperties();
    }

    @Test
    @DisplayName("tokens are strictly increasing and keep lexical order aligned with numeric order")
    void shouldIssueIncreasingTokens() {
        RedisFencingTokenSequence sequence = new RedisFencingTokenSequence(redis, props);

        String first = sequence.nextToken();
        String second = sequence.nextToken();
        String third = sequence.nextToken();

        assertNotEquals(first, second);
        assertTrue(second.compareTo(first) > 0, "tokens must be ordered, not merely unique");
        assertTrue(third.compareTo(second) > 0, "tokens must be ordered, not merely unique");
        assertEquals(19, first.length(), "the width is fixed so compareTo agrees with the numeric order");
        assertTrue(first.startsWith("0"), "the padding keeps the lexical order aligned with the numeric one");
    }

    @Test
    @DisplayName("the counter never expires, so a token can never come back")
    void shouldNeverExpireTheCounter() {
        RedisFencingTokenSequence sequence = new RedisFencingTokenSequence(redis, props);

        sequence.nextToken();

        Long ttl = redis.getExpire(props.getKeyPrefix() + ":fencing:seq");
        assertEquals(-1L, ttl,
                "the fencing counter must not carry a TTL (-1 means no expiry): a counter that "
                        + "expired would restart and reissue a token that is already in use");
    }

    @Test
    @DisplayName("the replication acknowledgement wait does not stop tokens being issued")
    void shouldIssueTokensEvenWhenNoReplicaAcknowledges() {
        // A single-node Redis has no replica to acknowledge the write, so the wait returns
        // nothing. The token must still be issued: blocking here would stall every claim
        // on a deployment that simply has no replica.
        props.getFencing().setWaitForReplicas(1);
        RedisFencingTokenSequence sequence = new RedisFencingTokenSequence(redis, props);

        String token = sequence.nextToken();

        assertTrue(token.endsWith("1"), "the first token from a fresh counter is 1, but was " + token);
    }

    @Test
    @DisplayName("the wait can be switched off")
    void shouldIssueTokensWithoutTheWait() {
        props.getFencing().setWaitForReplicas(0);
        RedisFencingTokenSequence sequence = new RedisFencingTokenSequence(redis, props);

        assertEquals(1L, redis.opsForValue().increment(props.getKeyPrefix() + ":fencing:seq"));
        assertTrue(sequence.nextToken().endsWith("2"));
    }
}
