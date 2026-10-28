package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.model.JobPriority;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSubmissionTest {

    @Test
    void shouldBuildWithDefaults() {
        JobSubmission sub = JobSubmission.builder("email.send").build();

        assertEquals("email.send", sub.getJobType());
        assertNotNull(sub.getIdempotencyKey());
        assertFalse(sub.getIdempotencyKey().isBlank());
        assertEquals("default", sub.getProducer());
        assertEquals(JobPriority.NORMAL, sub.getPriority());
        assertNull(sub.getPayload());
        assertNull(sub.getUniqueKey());
        assertNull(sub.getRunAt());
        assertNull(sub.getMaxAttempts());
        assertNull(sub.getTimeoutSeconds());
        assertNull(sub.getCallbackUrl());
    }

    @Test
    void shouldBuildWithCustomOptions() {
        Instant specificRunAt = Instant.now().plusSeconds(300);
        JobSubmission sub = JobSubmission.of("report.generate", Map.of("reportId", 99))
                .idempotencyKey("rep-99")
                .uniqueKey("unique-tenant-1")
                .priority(JobPriority.HIGH)
                .producer("reporting-service")
                .runAt(specificRunAt)
                .maxAttempts(5)
                .timeout(Duration.ofMinutes(2))
                .callbackUrl("https://example.com/callback")
                .build();

        assertEquals("report.generate", sub.getJobType());
        assertEquals(Map.of("reportId", 99), sub.getPayload());
        assertEquals("rep-99", sub.getIdempotencyKey());
        assertEquals("unique-tenant-1", sub.getUniqueKey());
        assertEquals(JobPriority.HIGH, sub.getPriority());
        assertEquals("reporting-service", sub.getProducer());
        assertEquals(specificRunAt, sub.getRunAt());
        assertEquals(5, sub.getMaxAttempts());
        assertEquals(120, sub.getTimeoutSeconds());
        assertEquals("https://example.com/callback", sub.getCallbackUrl());
    }

    @Test
    void shouldComputeDelayCorrectly() {
        Instant before = Instant.now();
        JobSubmission sub = JobSubmission.builder("delayed.task")
                .delay(Duration.ofSeconds(10))
                .build();
        Instant after = Instant.now();

        assertNotNull(sub.getRunAt());
        assertTrue(sub.getRunAt().isAfter(before.plusSeconds(9)));
        assertTrue(sub.getRunAt().isBefore(after.plusSeconds(11)));
    }

    @Test
    void shouldRejectInvalidArguments() {
        assertThrows(NullPointerException.class, () -> JobSubmission.builder(null));
        assertThrows(IllegalArgumentException.class, () -> JobSubmission.builder("   ").build());
        assertThrows(IllegalArgumentException.class, () -> JobSubmission.builder("task").maxAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> JobSubmission.builder("task").timeoutSeconds(0));
    }
}
