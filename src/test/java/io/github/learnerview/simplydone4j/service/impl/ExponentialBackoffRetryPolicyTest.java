package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void shouldStartAtTheInitialDelayForTheFirstRetry() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(500L, 2.0, 30_000L, 0.0);

        assertEquals(500L, policy.calculateDelayMs(0));
        assertEquals(1_000L, policy.calculateDelayMs(1));
        assertEquals(2_000L, policy.calculateDelayMs(2));
    }

    @Test
    void shouldCapTheDelay() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(500L, 2.0, 3_000L, 0.0);

        assertEquals(3_000L, policy.calculateDelayMs(3));
        assertEquals(3_000L, policy.calculateDelayMs(9));
    }

    @Test
    void shouldNotOverflowOrParkAJobForeverOnAHugeAttemptCount() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(500L, 10.0, 30_000L, 0.0);

        assertEquals(30_000L, policy.calculateDelayMs(10_000));
    }

    @Test
    void shouldKeepJitterInsideTheCap() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(1_000L, 2.0, 1_000L, 0.5);

        for (int i = 0; i < 200; i++) {
            long delay = policy.calculateDelayMs(0);
            assertTrue(delay >= 500L && delay <= 1_000L,
                    "jitter must not exceed the configured cap, was " + delay);
        }
    }

    @Test
    void shouldSpreadDelaysAroundTheBaseValue() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(1_000L, 2.0, 30_000L, 0.2);

        for (int i = 0; i < 200; i++) {
            long delay = policy.calculateDelayMs(0);
            assertTrue(delay >= 800L && delay <= 1_200L, "delay " + delay + " outside +/-20%");
        }
    }

    @Test
    void shouldSanitiseUnusableConfiguration() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(-1L, 0.0, -1L, -1.0);

        assertEquals(0L, policy.calculateDelayMs(0));
        assertEquals(0L, policy.calculateDelayMs(4));
    }

    @Test
    void shouldMapTheJobRetryConfigurationFromSecondsToMilliseconds() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRetry().setInitialDelaySeconds(5L);
        props.getRetry().setBackoffMultiplier(3.0);
        props.getRetry().setMaxDelaySeconds(20L);
        props.getRetry().setJitterFactor(0.0);

        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(props);

        assertEquals(5_000L, policy.calculateDelayMs(0));
        assertEquals(15_000L, policy.calculateDelayMs(1));
        assertEquals(20_000L, policy.calculateDelayMs(2));
    }
}
