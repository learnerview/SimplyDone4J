package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.api.spi.AdmissionDecision;
import io.github.learnerview.simplydone4j.api.spi.AdmissionPolicy;
import io.github.learnerview.simplydone4j.api.spi.AdmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.exception.AdmissionRejectedException;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.JobSubmissionInProgressException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.repository.IdempotencyStore;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobSubmissionServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock JobMapper jobMapper;
    @Mock JobEventPublisher eventPublisher;
    @Mock IdempotencyStore idempotencyStore;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobSubmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new JobSubmissionServiceImpl(jobRepo, queueRepo, AdmissionPolicy.allowAll(), props, jobMapper,
                eventPublisher, idempotencyStore, mock(Validator.class));
    }

    @Test
    void shouldSubmitNewJob() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);
        when(idempotencyStore.commit(anyString(), anyString(), anyString())).thenReturn(true);

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

        when(idempotencyStore.claim(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyStore.Reservation("job-1", false)));
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(existing));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertEquals("job-1", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldNotReturnAJobIdWhileItsSubmissionIsStillInProgress() {
        when(idempotencyStore.claim(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyStore.Reservation("job-pending", true)));
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobRepo.findById("job-pending")).thenReturn(Optional.of(JobEntity.builder()
                .id("job-pending")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build()));
        when(queueRepo.contains("job-pending", JobPriority.NORMAL)).thenReturn(false);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        JobSubmissionInProgressException thrown = assertThrows(
                JobSubmissionInProgressException.class, () -> service.submit("producer-1", req));

        assertEquals(java.time.Duration.ofSeconds(1), thrown.getRetryAfter());
        verify(jobRepo, never()).save(any());
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void shouldAdoptAnInFlightSubmissionThatWasAlreadyEnqueued() {
        JobEntity running = JobEntity.builder()
                .id("job-running")
                .jobType("test")
                .status(JobStatus.RUNNING)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();
        when(idempotencyStore.claim(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyStore.Reservation("job-running", true)));
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobRepo.findById("job-running")).thenReturn(Optional.of(running));
        when(idempotencyStore.commit("producer-1", "key-1", "job-running")).thenReturn(true);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-running", response.getJobId());
        verify(jobRepo, never()).save(any());
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void shouldThrowWhenQueueFull() {
        props.getQueue().setMaxDepth(1);
        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
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
    void shouldReturnAcceptedJobBeforeConsultingTheAdmissionPolicyOrCapacity() {
        // A retried submission of an already-accepted job must not be rejected just
        // because the queue has filled up or the policy has changed its mind in the
        // meantime.
        props.getQueue().setMaxDepth(1);

        AtomicInteger consultations = new AtomicInteger();
        AdmissionPolicy counting = request -> {
            consultations.incrementAndGet();
            return AdmissionDecision.allow();
        };
        JobSubmissionServiceImpl countingService = new JobSubmissionServiceImpl(jobRepo, queueRepo, counting, props,
                jobMapper, eventPublisher, idempotencyStore, mock(Validator.class));

        JobEntity existing = JobEntity.builder()
                .id("job-accepted")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .nextRunAt(java.time.Instant.now())
                .build();
        when(idempotencyStore.lookup("producer-1", "key-1"))
                .thenReturn(Optional.of("job-accepted"));
        when(jobRepo.findById("job-accepted")).thenReturn(Optional.of(existing));

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        JobSubmissionResponse response = countingService.submit("producer-1", req);

        assertEquals("job-accepted", response.getJobId());
        assertEquals(0, consultations.get());
        verify(idempotencyStore, never()).claim(anyString(), anyString(), anyString());
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldRejectTheSubmissionWhenTheAdmissionPolicySaysNo() {
        JobSubmissionServiceImpl rejecting = new JobSubmissionServiceImpl(jobRepo, queueRepo,
                request -> AdmissionDecision.reject(java.time.Duration.ofSeconds(7), "quota exceeded"), props,
                jobMapper, eventPublisher, idempotencyStore, mock(Validator.class));

        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        AdmissionRejectedException thrown = assertThrows(AdmissionRejectedException.class,
                () -> rejecting.submit("producer-1", req));

        assertEquals("quota exceeded", thrown.getMessage());
        assertEquals(java.time.Duration.ofSeconds(7), thrown.getRetryAfter());
        // A rejected submission never reaches the queue, never becomes a job, and never
        // consumes the idempotency key.
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
        verify(jobRepo, never()).save(any());
        verify(idempotencyStore, never()).claim(anyString(), anyString(), anyString());
    }

    @Test
    void shouldAdmitEverythingWhenNoPolicyIsSupplied() {
        JobSubmissionServiceImpl withoutPolicy = new JobSubmissionServiceImpl(jobRepo, queueRepo, null, props,
                jobMapper, eventPublisher, idempotencyStore, mock(Validator.class));

        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);
        when(idempotencyStore.commit(anyString(), anyString(), anyString())).thenReturn(true);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertNotNull(withoutPolicy.submit("producer-1", req));
    }

    @Test
    void shouldTellThePolicyWhatWasSubmitted() {
        AdmissionPolicy recording = mock(AdmissionPolicy.class);
        when(recording.decide(any())).thenReturn(AdmissionDecision.allow());
        JobSubmissionServiceImpl recordingService = new JobSubmissionServiceImpl(jobRepo, queueRepo, recording, props,
                jobMapper, eventPublisher, idempotencyStore, mock(Validator.class));

        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.HIGH);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);
        when(idempotencyStore.commit(anyString(), anyString(), anyString())).thenReturn(true);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        req.setPriority("HIGH");

        recordingService.submit("producer-1", req);

        ArgumentCaptor<AdmissionRequest> captor = ArgumentCaptor.forClass(AdmissionRequest.class);
        verify(recording).decide(captor.capture());
        assertEquals("producer-1", captor.getValue().producer());
        assertEquals("test", captor.getValue().jobType());
        assertEquals(JobPriority.HIGH, captor.getValue().priority());
        assertNotNull(captor.getValue().submittedAt());
    }

    @Test
    void shouldRollBackSavedJobAndIdempotencyWhenCapacityIsRejected() {
        props.getQueue().setMaxDepth(10);
        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(false);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(QueueFullException.class, () -> service.submit("producer-1", req));

        verify(jobRepo).delete(anyString());
        verify(idempotencyStore).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldRollBackSavedJobAndIdempotencyWhenEnqueueThrows() {
        when(idempotencyStore.claim(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
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
        verify(idempotencyStore).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldRefuseToCancelWhenTheStatusMovedOn() {
        // The worker won the race and claimed the job; the compare-and-set in the store
        // must lose and cancel must not overwrite the RUNNING transition.
        JobEntity queued = JobEntity.builder()
                .id("job-1")
                .jobType("test")
                .status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL)
                .producer("producer-1")
                .build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued));
        when(jobRepo.requestCancellation("job-1", "user")).thenReturn(CancelOutcome.NOT_CANCELLABLE);

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
        when(jobRepo.requestCancellation("job-1", "user")).thenReturn(CancelOutcome.CANCELLED);

        service.cancelJob("job-1");

        verify(queueRepo).remove("job-1", JobPriority.NORMAL);
        // The state change happened inside the store's compare-and-set. The service must
        // not write the job again: an unfenced save here could land after the job was
        // reaped and resurrect it.
        verify(jobRepo, never()).save(any());
        verify(eventPublisher).publish(eq(JobEvent.JOB_CANCELLED), any());
    }

    @Test
    void shouldLeaveATerminalStateToTheWorkerWhenTheJobIsAlreadyRunning() {
        // A running job cannot be un-run. The request is recorded and the worker stops
        // the handler and writes the terminal state, so the service must not write it.
        when(jobRepo.requestCancellation("job-1", "user")).thenReturn(CancelOutcome.REQUESTED);

        service.cancelJob("job-1");

        verify(jobRepo, never()).save(any());
        verify(queueRepo, never()).remove(anyString(), any());
        verify(eventPublisher, never()).publish(eq(JobEvent.JOB_CANCELLED), any());
    }

    @Test
    void shouldReportAMissingJob() {
        when(jobRepo.requestCancellation("missing", "user")).thenReturn(CancelOutcome.NOT_FOUND);

        assertThrows(JobNotFoundException.class, () -> service.cancelJob("missing"));
    }
}
