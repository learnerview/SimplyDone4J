package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExponentialBackoffRetryPolicyTest {

    @Test
    void shouldKeepTheDeterministicDelayWhenJitterIsDisabled() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRetry().setInitialDelaySeconds(5);
        props.getRetry().setBackoffMultiplier(2.0);
        props.getRetry().setJitterFactor(0.0);
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(props);

        assertEquals(5_000L, policy.calculateDelayMs(0));
        assertEquals(10_000L, policy.calculateDelayMs(1));
        assertEquals(20_000L, policy.calculateDelayMs(2));
    }

    @Test
    void shouldSpreadDelaysWithinTheConfiguredJitterWindow() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRetry().setInitialDelaySeconds(5);
        props.getRetry().setBackoffMultiplier(2.0);
        props.getRetry().setJitterFactor(0.5);
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(props);

        for (int i = 0; i < 200; i++) {
            long delay = policy.calculateDelayMs(1);
            assertTrue(delay >= 5_000L && delay <= 15_000L,
                    "delay " + delay + " must stay inside the +/-50% window");
        }
    }

    @Test
    void shouldClampInvalidJitterFactors() {
        SimplyDoneProperties props = new SimplyDoneProperties();
        props.getRetry().setJitterFactor(5.0);
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(props);

        long delay = policy.calculateDelayMs(1);
        assertTrue(delay >= 0L && delay <= 20_000L,
                "an out-of-range jitter factor must be clamped to 100%");
    }
}
