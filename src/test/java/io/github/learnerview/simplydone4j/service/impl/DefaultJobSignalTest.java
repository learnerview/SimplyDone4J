package io.github.learnerview.simplydone4j.service.impl;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultJobSignalTest {

    @Test
    void shouldTimeOutWhenNoSignalArrives() throws InterruptedException {
        DefaultJobSignal signal = new DefaultJobSignal();

        long start = System.nanoTime();
        assertFalse(signal.await(100));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs >= 50, "await must not busy-return before the timeout");
    }

    @Test
    void shouldWakeImmediatelyAfterSignal() throws InterruptedException {
        DefaultJobSignal signal = new DefaultJobSignal();
        signal.signal();

        long start = System.nanoTime();
        assertTrue(signal.await(1_000));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsedMs < 500, "an already-signalled loop must not sit out the timeout");
    }

    @Test
    void shouldCoalesceSignalsIntoOneWakeUp() throws InterruptedException {
        DefaultJobSignal signal = new DefaultJobSignal();
        signal.signal();
        signal.signal();
        signal.signal();

        assertTrue(signal.await(100));
        assertFalse(signal.await(100),
                "draining queued permits keeps a burst of submissions to one extra poll");
    }

    @Test
    void shouldBeInterruptible() throws InterruptedException {
        DefaultJobSignal signal = new DefaultJobSignal();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().start(() -> {
            waiting.countDown();
            try {
                signal.await(10_000);
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });

        waiting.await(1, TimeUnit.SECONDS);
        waiter.interrupt();
        waiter.join(1_000);

        assertFalse(waiter.isAlive(), "a scheduler waiting for work must stop promptly on shutdown");
    }
}
