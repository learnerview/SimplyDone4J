package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fencing sequence issues the replication acknowledgement that makes a token
 * survive a Sentinel promotion.
 *
 * <p>This is a unit test because the command has to be visible: a test that only checks
 * that tokens still come out would pass with the acknowledgement quietly removed, which
 * is the change that would silently weaken the failover guarantee.</p>
 */
class RedisFencingTokenSequenceTest {

    private StringRedisTemplate redis;
    private RedisConnection connection;
    private RedisStringCommands stringCommands;
    private SimplyDoneProperties props;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        connection = mock(RedisConnection.class);
        stringCommands = mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(stringCommands.incr(any(byte[].class))).thenReturn(7L);
        when(redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        });
        props = new SimplyDoneProperties();
        props.setKeyPrefix("sd4j-test");
    }

    @Test
    @DisplayName("the increment is followed by a replication acknowledgement wait")
    void shouldWaitForReplicationAfterTheIncrement() {
        props.getFencing().setWaitForReplicas(1);

        new RedisFencingTokenSequence(redis, props).nextToken();

        InOrder inOrder = inOrder(stringCommands, connection);
        inOrder.verify(stringCommands).incr(any(byte[].class));
        inOrder.verify(connection).execute(eq("WAIT"),
                eq("1".getBytes(StandardCharsets.UTF_8)),
                eq("500".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("the wait is skipped by default so a single node does not block")
    void shouldSkipTheWaitByDefault() {
        assertEquals(0, new SimplyDoneProperties().getFencing().getWaitForReplicas());

        new RedisFencingTokenSequence(redis, props).nextToken();

        verify(connection, never()).execute(eq("WAIT"), any(byte[].class), any(byte[].class));
    }

    @Test
    @DisplayName("a failing acknowledgement wait still issues the token")
    void shouldIssueTheTokenEvenWhenTheWaitFails() {
        props.getFencing().setWaitForReplicas(1);
        when(connection.execute(eq("WAIT"), any(byte[].class), any(byte[].class)))
                .thenThrow(new RuntimeException("replica down"));

        String token = new RedisFencingTokenSequence(redis, props).nextToken();

        assertEquals("0000000000000000007", token,
                "the token was already issued by the increment; a failed wait must not "
                        + "stop the engine from claiming work");
    }

    @Test
    @DisplayName("the token is the zero-padded counter value")
    void shouldPadTheToken() {
        String token = new RedisFencingTokenSequence(redis, props).nextToken();

        assertTrue(token.startsWith("0000000000000000"), "but was " + token);
        assertTrue(token.endsWith("7"), "but was " + token);
    }
}
