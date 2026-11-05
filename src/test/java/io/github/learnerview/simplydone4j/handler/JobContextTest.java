package io.github.learnerview.simplydone4j.handler;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobContextTest {

    @Test
    void computesDeadlineFromExecutionStartNotSubmissionTime() {
        Instant createdAt = Instant.now().minusSeconds(3600);
        Instant startedAt = Instant.now();
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("test")
                .createdAt(createdAt)
                .startedAt(startedAt)
                .timeoutSeconds(30)
                .build();

        Instant deadline = JobContext.from(job).getDeadline();

        assertTrue(deadline.isAfter(startedAt.plusSeconds(29)));
        assertTrue(deadline.isBefore(startedAt.plusSeconds(31)));
    }

    @Test
    void fallsBackToTheExecutorDefaultTimeoutWhenTheJobHasNone() {
        Instant startedAt = Instant.now();
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("test")
                .startedAt(startedAt)
                .build();

        JobContext context = JobContext.from(job, 45);

        assertEquals(45, context.getTimeoutSeconds());
        assertTrue(context.getDeadline().isAfter(startedAt.plusSeconds(44)));
        assertTrue(context.getDeadline().isBefore(startedAt.plusSeconds(46)));
    }

    @Test
    void keepsTheJobsOwnTimeoutWhenOneIsSet() {
        Instant startedAt = Instant.now();
        JobEntity job = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .producer("test")
                .startedAt(startedAt)
                .timeoutSeconds(10)
                .build();

        JobContext context = JobContext.from(job, 45);

        assertEquals(10, context.getTimeoutSeconds());
        assertTrue(context.getDeadline().isAfter(startedAt.plusSeconds(9)));
        assertTrue(context.getDeadline().isBefore(startedAt.plusSeconds(11)));
    }
}
