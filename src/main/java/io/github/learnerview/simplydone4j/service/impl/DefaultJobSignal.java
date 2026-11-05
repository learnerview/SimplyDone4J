package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.service.JobSignal;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class DefaultJobSignal implements JobSignal {

    private final Semaphore permits = new Semaphore(0);

    @Override
    public void signal() {
        permits.release();
    }

    @Override
    public boolean await(long timeoutMs) throws InterruptedException {
        if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            return false;
        }
        permits.drainPermits();
        return true;
    }
}
