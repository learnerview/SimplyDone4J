package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock JobEventPublisher eventPublisher;

    SimplyDoneProperties props;
    DeadLetterServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        service = new DeadLetterServiceImpl(jobRepo, queueRepo, eventPublisher, props);
    }

    private JobEntity deadLettered(String id, int attemptCount, Instant updatedAt) {
        return JobEntity.builder()
                .id(id)
                .jobType("payment-capture")
                .producer("billing")
                .status(JobStatus.DLQ)
                .priority(JobPriority.HIGH)
                .payload("{\"amount\":100}")
                .attemptCount(attemptCount)
                .maxAttempts(3)
                .result("Max retries exceeded: gateway timeout")
                .startedAt(updatedAt == null ? null : updatedAt.minusSeconds(60))
                .completedAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }

    @Test
    void shouldListDeadLetteredJobsNewestFirst() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of(
                deadLettered("old", 3, older),
                deadLettered("new", 3, newer)));

        List<JobEntity> listed = service.listDeadLettered(10);

        assertEquals(List.of("new", "old"), listed.stream().map(JobEntity::getId).toList());
    }

    @Test
    void shouldRejectANonPositiveListLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.listDeadLettered(0));
        assertThrows(IllegalArgumentException.class, () -> service.listDeadLettered(-1));
        assertThrows(IllegalArgumentException.class, () -> service.requeueAll(0));
    }

    @Test
    void shouldRequeueADeadLetteredJobWithAFreshAttemptBudget() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        JobEntity result = service.requeue("job-1");

        assertEquals(JobStatus.QUEUED, result.getStatus());
        assertEquals(0, result.getAttemptCount());
        assertNotNull(result.getNextRunAt());
        // The payload and job type must survive: a requeue re-runs the same work.
        assertEquals("{\"amount\":100}", result.getPayload());
        assertEquals("payment-capture", result.getJobType());

        verify(jobRepo).save(job);
        verify(queueRepo).enqueue(eq("job-1"), eq(JobPriority.HIGH), any(Long.class));
    }

    @Test
    void shouldClearTerminalFieldsSoTheJobIsNotQueuedAndCompletedAtOnce() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        JobEntity result = service.requeue("job-1");

        assertNull(result.getCompletedAt());
        assertNull(result.getResult());
        assertNull(result.getVisibleAt());
        assertNull(result.getLeaseToken());
        assertNull(result.getLeaseOwner());
        // A requeued job must not still look like it is mid-execution; startedAt is only
        // written when the executor claims a QUEUED job, so a leftover value from the
        // failed run would persist while the job sits in the queue.
        assertNull(result.getStartedAt());
    }

    @Test
    void shouldFallBackToTheConfiguredBudgetWhenTheJobHasNone() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        job.setMaxAttempts(0);
        props.getRetry().setMaxAttempts(7);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        assertEquals(7, service.requeue("job-1").getMaxAttempts());
    }

    @Test
    void shouldRefuseToRequeueAJobWithNoAttemptBudget() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        job.setMaxAttempts(0);
        props.getRetry().setMaxAttempts(0);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.requeue("job-1"));
        assertTrue(thrown.getMessage().contains("no attempt budget"));
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldThrowNotFoundForAnUnknownJob() {
        when(jobRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> service.requeue("missing"));
    }

    @Test
    void shouldRefuseToRequeueAJobThatIsNotInTheDlq() {
        JobEntity job = deadLettered("job-1", 1, Instant.now());
        job.setStatus(JobStatus.RUNNING);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> service.requeue("job-1"));
        assertTrue(thrown.getMessage().contains("RUNNING"));
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldPublishAnEventOnRequeue() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        service.requeue("job-1");

        verify(eventPublisher).publish(eq(JobEvent.JOB_CREATED), any());
    }

    @Test
    void shouldRequeueABatchAndReturnOnlyWhatMoved() {
        JobEntity a = deadLettered("a", 3, Instant.parse("2026-03-03T00:00:00Z"));
        JobEntity b = deadLettered("b", 3, Instant.parse("2026-03-02T00:00:00Z"));
        JobEntity c = deadLettered("c", 3, Instant.parse("2026-03-01T00:00:00Z"));
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of(a, b, c));
        when(jobRepo.findById(anyString())).thenAnswer(inv ->
                Optional.of(switch ((String) inv.getArgument(0)) {
                    case "a" -> a;
                    case "b" -> b;
                    default -> c;
                }));

        List<JobEntity> requeued = service.requeueAll(3);

        assertEquals(List.of("a", "b", "c"), requeued.stream().map(JobEntity::getId).toList());
    }

    @Test
    void shouldKeepGoingWhenOneJobInABatchCannotBeRequeued() {
        JobEntity a = deadLettered("a", 3, Instant.parse("2026-03-03T00:00:00Z"));
        JobEntity b = deadLettered("b", 3, Instant.parse("2026-03-02T00:00:00Z"));
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of(a, b));
        when(jobRepo.findById("a")).thenReturn(Optional.of(a));
        // b vanished between the listing and the requeue, e.g. purged by retention.
        when(jobRepo.findById("b")).thenReturn(Optional.empty());

        List<JobEntity> requeued = service.requeueAll(3);

        assertEquals(List.of("a"), requeued.stream().map(JobEntity::getId).toList());
        verify(queueRepo).enqueue(eq("a"), any(JobPriority.class), any(Long.class));
        verify(queueRepo, never()).enqueue(eq("b"), any(JobPriority.class), any(Long.class));
    }

    @Test
    void shouldHonourTheBatchLimit() {
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of(
                deadLettered("a", 3, Instant.parse("2026-03-03T00:00:00Z")),
                deadLettered("b", 3, Instant.parse("2026-03-02T00:00:00Z")),
                deadLettered("c", 3, Instant.parse("2026-03-01T00:00:00Z"))));

        assertEquals(2, service.listDeadLettered(2).size());
    }

    @Test
    void shouldEnqueueUsingTheRequeueInstantNotAStaleTimestamp() {
        JobEntity job = deadLettered("job-1", 3, Instant.now());
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        Instant before = Instant.now();
        service.requeue("job-1");
        Instant after = Instant.now();

        ArgumentCaptor<Long> scoreCaptor = ArgumentCaptor.forClass(Long.class);
        verify(queueRepo).enqueue(eq("job-1"), eq(JobPriority.HIGH), scoreCaptor.capture());

        long score = scoreCaptor.getValue();
        assertTrue(score >= before.toEpochMilli() && score <= after.toEpochMilli(),
                "A requeued job must be immediately claimable, not scheduled into the past");
    }

    @Test
    void shouldNotEnqueueAnythingWhenThereIsNothingToRequeue() {
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of());

        assertTrue(service.requeueAll(10).isEmpty());
        verify(jobRepo, never()).save(any());
        verify(queueRepo, never()).enqueue(anyString(), any(JobPriority.class), any(Long.class));
    }

    @Test
    void shouldIgnoreUndatedRecordsRatherThanFailingTheListing() {
        JobEntity undated = deadLettered("undated", 3, null);
        undated.setUpdatedAt(null);
        JobEntity dated = deadLettered("dated", 3, Instant.parse("2026-01-01T00:00:00Z"));
        when(jobRepo.findByStatus(JobStatus.DLQ)).thenReturn(List.of(undated, dated));

        assertEquals(List.of("dated", "undated"),
                service.listDeadLettered(10).stream().map(JobEntity::getId).toList());
    }
}
