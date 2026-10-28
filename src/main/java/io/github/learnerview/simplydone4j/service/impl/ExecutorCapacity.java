package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.service.WorkerCapacity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

public final class ExecutorCapacity implements WorkerCapacity {

    private final ThreadPoolTaskExecutor executor;

    public ExecutorCapacity(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public int availableSlots() {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        if (pool == null) {
            return 0;
        }
        int active = executor.getActiveCount();
        int core = executor.getCorePoolSize();
        int running = Math.min(active, core);
        return Math.max(0, core - running);
    }

    @Override
    public int maxConcurrency() {
        return executor.getCorePoolSize();
    }
}
