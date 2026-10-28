package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.service.JobSignal;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The default {@link JobSignal}: a latch with a timeout.
 *
 * <p>A signal that arrives while nobody is waiting is remembered, so a submission that
 * lands between two polls is not lost. The wait therefore ends either when a signal
 * arrives or when the timeout passes, whichever comes first.</p>
 */
public final class DefaultJobSignal implements JobSignal {

    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private boolean pending;

    @Override
    public void signal() {
        lock.lock();
        try {
            pending = true;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean await(Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        if (nanos < 0L) {
            nanos = 0L;
        }
        lock.lock();
        try {
            while (!pending && nanos > 0L) {
                nanos = condition.awaitNanos(nanos);
            }
            boolean arrived = pending;
            pending = false;
            return arrived;
        } finally {
            lock.unlock();
        }
    }
}
