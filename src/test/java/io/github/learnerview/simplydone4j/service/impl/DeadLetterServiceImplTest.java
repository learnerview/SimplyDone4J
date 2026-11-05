package io.github.learnerview.simplydone4j.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventData;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.JobSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock JobEventPublisher eventPublisher;
    @Mock JobSignal signal;

    private DeadLetterServiceImpl service;
    private JobMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JobMapper(new ObjectMapper());
        service = new DeadLetterServiceImpl(jobRepo, queueRepo, mapper, eventPublisher, signal,
                new SimplyDoneProperties());
    }

    @Test
    void listsOnlyTheRequestedNumberOfDeadLetterJobs() {
        when(jobRepo.findByStatus(JobStatus.DLQ, 5))
                .thenReturn(List.of(deadLetter("job-1"), deadLetter("job-2")));

        List<JobResponse> result = service.list(5);

        assertEquals(2, result.size());
        assertEquals("job-1", result.get(0).getId());
        verify(jobRepo).findByStatus(JobStatus.DLQ, 5);
    }

    @Test
    void defaultsTheListLimit() {
        when(jobRepo.findByStatus(JobStatus.DLQ, 100)).thenReturn(List.of());

        assertTrue(service.list().isEmpty());
    }

    @Test
    void countsDeadLetterJobs() {
        when(jobRepo.countByStatus(JobStatus.DLQ)).thenReturn(7L);

        assertEquals(7L, service.count());
    }

    @Test
    void replayResetsTheJobAndAdmitsItToTheQueue() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.saveIfStatus(any(JobEntity.class), eq(JobStatus.DLQ))).thenReturn(true);
        when(queueRepo.enqueueIfUnderCapacity(eq("job-1"), eq(JobPriority.HIGH), anyLong(), anyLong()))
                .thenReturn(true);

        JobResponse response = service.replay("job-1");

        ArgumentCaptor<JobEntity> replayed = ArgumentCaptor.forClass(JobEntity.class);
        verify(jobRepo).saveIfStatus(replayed.capture(), eq(JobStatus.DLQ));
        JobEntity written = replayed.getValue();
        assertEquals(JobStatus.QUEUED, written.getStatus());
        assertEquals(0, written.getAttemptCount());
        assertEquals(3, written.getMaxAttempts());
        assertNull(written.getResult());
        assertNull(written.getLeaseOwner());
        assertNull(written.getLeaseToken());
        assertNull(written.getVisibleAt());
        assertEquals("job-1", response.getId());
        verify(signal).signal();
        verify(eventPublisher).publish(eq(JobEvent.JOB_REPLAYED), any(JobEventData.class));
    }

    @Test
    void replayFailsWhenTheJobIsNoLongerDeadLettered() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.saveIfStatus(any(JobEntity.class), eq(JobStatus.DLQ))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.replay("job-1"));
        verifyNoInteractions(queueRepo, signal, eventPublisher);
    }

    @Test
    void replayRestoresTheDeadLetterRecordWhenTheQueueIsFull() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.saveIfStatus(any(JobEntity.class), eq(JobStatus.DLQ))).thenReturn(true);
        when(queueRepo.enqueueIfUnderCapacity(eq("job-1"), eq(JobPriority.HIGH), anyLong(), anyLong()))
                .thenReturn(false);
        when(jobRepo.saveIfStatus(original, JobStatus.QUEUED)).thenReturn(true);

        assertThrows(QueueFullException.class, () -> service.replay("job-1"));
        verify(jobRepo).saveIfStatus(original, JobStatus.QUEUED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void replayTreatsAnAmbiguousAdmissionAsAcceptedWhenTheQueueEntryExists() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.saveIfStatus(any(JobEntity.class), eq(JobStatus.DLQ))).thenReturn(true);
        when(queueRepo.enqueueIfUnderCapacity(eq("job-1"), eq(JobPriority.HIGH), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("timeout"));
        when(queueRepo.contains("job-1", JobPriority.HIGH)).thenReturn(true);

        assertDoesNotThrow(() -> service.replay("job-1"));
        verify(eventPublisher).publish(eq(JobEvent.JOB_REPLAYED), any(JobEventData.class));
    }

    @Test
    void replayPropagatesAnUnknownAdmissionFailure() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.saveIfStatus(any(JobEntity.class), eq(JobStatus.DLQ))).thenReturn(true);
        when(queueRepo.enqueueIfUnderCapacity(eq("job-1"), eq(JobPriority.HIGH), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("timeout"));
        when(queueRepo.contains("job-1", JobPriority.HIGH)).thenReturn(false);

        assertThrows(RuntimeException.class, () -> service.replay("job-1"));
    }

    @Test
    void discardMovesTheJobOutOfTheDeadLetterIndexAndDeletesIt() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.DLQ, JobStatus.FAILED)).thenReturn(true);

        service.discard("job-1");

        verify(jobRepo).delete("job-1");
        verify(eventPublisher).publish(eq(JobEvent.JOB_DISCARDED), any(JobEventData.class));
    }

    @Test
    void discardFailsWhenAReplayWonTheRace() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.DLQ, JobStatus.FAILED)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.discard("job-1"));
        verify(jobRepo, never()).delete(anyString());
    }

    @Test
    void discardKeepsAnAuditableRecordWhenDeletionFails() {
        JobEntity original = deadLetter("job-1");
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(original));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.DLQ, JobStatus.FAILED)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(jobRepo).delete("job-1");
        when(jobRepo.saveIfStatus(original, JobStatus.FAILED)).thenReturn(true);

        assertThrows(RuntimeException.class, () -> service.discard("job-1"));
        verify(jobRepo).saveIfStatus(original, JobStatus.FAILED);
    }

    @Test
    void rejectsJobsThatAreNotInTheDeadLetterQueue() {
        JobEntity running = deadLetter("job-1");
        running.setStatus(JobStatus.RUNNING);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(running));

        assertThrows(IllegalArgumentException.class, () -> service.replay("job-1"));
        assertThrows(IllegalArgumentException.class, () -> service.discard("job-1"));
    }

    @Test
    void reportsMissingJobs() {
        when(jobRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> service.replay("missing"));
    }

    private static JobEntity deadLetter(String id) {
        Instant now = Instant.now();
        return JobEntity.builder()
                .id(id)
                .jobType("email")
                .producer("producer-1")
                .status(JobStatus.DLQ)
                .priority(JobPriority.HIGH)
                .attemptCount(3)
                .maxAttempts(3)
                .result("Max retries exceeded")
                .leaseOwner("worker-1")
                .leaseToken("token")
                .visibleAt(now.plusSeconds(30))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
