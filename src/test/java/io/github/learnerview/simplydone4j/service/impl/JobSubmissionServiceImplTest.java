package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobSubmissionServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock io.github.learnerview.simplydone4j.service.RateLimiterService rateLimiter;
    @Mock JobMapper jobMapper;
    @Mock JobEventPublisher eventPublisher;
    @Mock IdempotencyService idempotencyService;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobSubmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new JobSubmissionServiceImpl(jobRepo, queueRepo, rateLimiter, props, jobMapper, eventPublisher,
                idempotencyService, mock(Validator.class));
    }

    @Test
    void shouldSubmitNewJob() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertNotNull(response.getJobId());
        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo).save(any(JobEntity.class));
        verify(queueRepo).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        verify(jobRepo, never()).delete(anyString());
    }

    @Test
    void shouldReturnExistingJobOnIdempotentSubmit() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        JobEntity existing = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();

        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.of("job-1"));
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(existing));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertEquals("job-1", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldThrowWhenQueueFull() {
        props.getQueue().setMaxDepth(1);
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        // The admission itself reports that the queue was at capacity.
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(false);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(QueueFullException.class, () -> service.submit("producer-1", req));
    }

    @Test
    void shouldReturnAcceptedJobBeforeChargingRateLimitOrCapacity() {
        // A retried submission of an already-accepted job must not be rejected just
        // because the queue has filled up in the meantime.
        props.getQueue().setMaxDepth(1);

        JobEntity existing = JobEntity.builder()
                .id("job-accepted")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .nextRunAt(java.time.Instant.now())
                .build();
        when(jobRepo.findByProducerAndIdempotencyKey("producer-1", "key-1"))
                .thenReturn(Optional.of(existing));

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-accepted", response.getJobId());
        verify(rateLimiter, never()).checkRateLimit(anyString());
        verify(idempotencyService, never()).acquireOrGetExisting(anyString(), anyString(), anyString());
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldRollBackSavedJobAndIdempotencyWhenCapacityIsRejected() {
        props.getQueue().setMaxDepth(10);
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(false);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(QueueFullException.class, () -> service.submit("producer-1", req));

        verify(jobRepo).delete(anyString());
        verify(idempotencyService).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldRollBackSavedJobAndIdempotencyWhenEnqueueThrows() {
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.submit("producer-1", req));
        assertEquals("redis down", thrown.getMessage());

        verify(jobRepo).delete(anyString());
        verify(idempotencyService).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldRefuseToCancelWhenTheStatusMovedOn() {
        // The worker won the race and claimed the job; the CAS must lose and cancel must
        // not overwrite the RUNNING transition.
        JobEntity queued = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.QUEUED, JobStatus.CANCELLED))
                .thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.cancelJob("job-1"));

        verify(queueRepo, never()).remove(anyString(), any());
        verify(jobRepo, never()).save(any());
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    void shouldCancelQueuedJobViaAtomicTransition() {
        JobEntity queued = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.QUEUED, JobStatus.CANCELLED))
                .thenReturn(true);

        service.cancelJob("job-1");

        verify(queueRepo).remove("job-1", JobPriority.NORMAL);
        verify(jobRepo).save(queued);
        assertEquals(JobStatus.CANCELLED, queued.getStatus());
        verify(eventPublisher).publish(eq(JobEvent.JOB_CANCELLED), any());
    }
}
