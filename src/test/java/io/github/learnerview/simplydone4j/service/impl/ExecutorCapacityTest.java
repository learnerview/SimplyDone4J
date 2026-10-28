package io.github.learnerview.simplydone4j.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorCapacityTest {

    private ThreadPoolTaskExecutor executor;
    private ExecutorCapacity capacity;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.initialize();
        capacity = new ExecutorCapacity(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    /**
     * Before any task is submitted the executor's pool size is zero, but the core threads
     * are exactly the capacity it can start. Reading the pool size alone reported no free
     * slots and the scheduler claimed nothing until unrelated work had grown the pool.
     */
    @Test
    void shouldReportCoreCapacityBeforeAnyTaskIsSubmitted() {
        assertEquals(0, executor.getThreadPoolExecutor().getPoolSize(), "precondition");

        assertEquals(2, capacity.availableSlots());
        assertEquals(2, capacity.maxConcurrency());
    }

    @Test
    void shouldSubtractRunningTasksFromTheAvailableSlots() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS), "both workers should have started");

        long deadline = System.currentTimeMillis() + 5000;
        while (executor.getActiveCount() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(2, executor.getActiveCount(), "precondition");
        assertEquals(0, capacity.availableSlots(),
                "two of the two usable threads are busy, so nothing can start");
        release.countDown();
    }
}
