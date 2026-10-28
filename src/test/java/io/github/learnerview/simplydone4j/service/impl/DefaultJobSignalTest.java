package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.service.JobSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultJobSignalTest {

    private final JobSignal signal = new DefaultJobSignal();

    @Test
    void returnsWhenTheTimeoutPassesAndNothingSignalled() throws Exception {
        long start = System.nanoTime();

        boolean arrived = signal.await(Duration.ofMillis(50));

        assertThat(arrived).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(40));
    }

    @Test
    void returnsImmediatelyWhenASignalArrivedFirst() throws Exception {
        signal.signal();

        boolean arrived = signal.await(Duration.ofSeconds(30));

        assertThat(arrived).isTrue();
    }

    /**
     * A signal that arrives between two polls must not be lost: the next wait has to
     * return at once, otherwise the job that was just submitted waits for a full poll
     * interval for no reason.
     */
    @Test
    void doesNotLoseASignalThatArrivedWhileNobodyWasWaiting() throws Exception {
        signal.signal();

        assertThat(signal.await(Duration.ofSeconds(30))).isTrue();
    }

    /**
     * Ten submissions need one wake-up, not ten. The signal is coalescing on purpose:
     * the scheduler drains everything it can claim in one pass anyway.
     */
    @Test
    void coalescesManySignalsIntoOne() throws Exception {
        for (int i = 0; i < 10; i++) {
            signal.signal();
        }

        assertThat(signal.await(Duration.ofSeconds(30))).isTrue();
        assertThat(signal.await(Duration.ofMillis(50))).isFalse();
    }

    @Test
    void wakesAWaitingThread() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean arrived = new AtomicBoolean();

        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                arrived.set(signal.await(Duration.ofSeconds(30)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        signal.signal();
        waiter.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(arrived).isTrue();
    }
}
