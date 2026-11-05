package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.exception.QueueFullException;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.IdempotencyReservation;
import io.github.learnerview.simplydone4j.service.IdempotencyService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobSubmissionServiceImplTest {

    @Mock JobRepository jobRepo;
    @Mock QueueRepository queueRepo;
    @Mock JobMapper jobMapper;
    @Mock JobEventPublisher eventPublisher;
    @Mock IdempotencyService idempotencyService;

    SimplyDoneProperties props = new SimplyDoneProperties();
    JobSubmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        props.getSubmission().setReservationRecordWaitMs(50);
        props.getSubmission().setAdmissionWaitMs(50);
        props.getSubmission().setResolvePollIntervalMs(5);
        service = new JobSubmissionServiceImpl(jobRepo, queueRepo, props, jobMapper, eventPublisher,
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
        when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).build()));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertNotNull(response.getJobId());
        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo).save(any(JobEntity.class));
        verify(idempotencyService).confirmReservation(eq("producer-1"), eq("key-1"), anyString());
        verify(queueRepo).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
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

        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString())).thenReturn(Optional.of(new IdempotencyReservation("job-1", false)));
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(existing));
        when(queueRepo.contains("job-1", JobPriority.NORMAL)).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response);
        assertEquals("job-1", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldRejectAPayloadLargerThanTheConfiguredLimit() {
        props.getSubmission().setMaxPayloadBytes(5);
        JobSubmissionServiceImpl limited = new JobSubmissionServiceImpl(jobRepo, queueRepo, props,
                jobMapper, eventPublisher, idempotencyService, mock(Validator.class));
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        req.setPayload(java.util.Map.of("message", "too large"));
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("123456");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> limited.submit("producer-1", req));

        assertTrue(error.getMessage().contains("max-payload-bytes"));
        verify(jobRepo, never()).save(any());
        verify(jobRepo).delete(anyString());
        verify(idempotencyService).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldAllowTheDefaultPayloadLimit() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        req.setPayload(java.util.Map.of("message", "ok"));
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{\"message\":\"ok\"}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);
        when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).build()));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo).save(any(JobEntity.class));
    }

    @Test
    void shouldThrowWhenQueueFull() {
        props.getQueue().setMaxDepth(1);
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(false);

        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(QueueFullException.class, () -> service.submit("producer-1", req));
        verify(jobRepo).delete(anyString());
        verify(idempotencyService).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
    }

    @Test
    void shouldRejectAValidationFailureBeforeTouchingRedis() {
        Validator validator = mock(Validator.class);
        @SuppressWarnings("unchecked")
        ConstraintViolation<JobSubmissionRequest> violation = mock(ConstraintViolation.class);
        JobSubmissionRequest req = new JobSubmissionRequest();
        when(validator.validate(req)).thenReturn(Set.of(violation));
        JobSubmissionServiceImpl validating = new JobSubmissionServiceImpl(jobRepo, queueRepo,
                props, jobMapper, eventPublisher, idempotencyService, validator);

        assertThrows(IllegalArgumentException.class, () -> validating.submit("producer-1", req));
        verifyNoInteractions(jobRepo, queueRepo, idempotencyService);
    }

    @Test
    void shouldRejectABlankProducer() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");

        assertThrows(IllegalArgumentException.class, () -> service.submit("  ", req));
        verifyNoInteractions(jobRepo, queueRepo, idempotencyService);
    }

    @Test
    void shouldReturnAnAlreadyAcceptedJobBeforeAcquiringAReservation() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        JobEntity existing = JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.RUNNING)
                .priority(JobPriority.HIGH).nextRunAt(java.time.Instant.now()).build();
        when(jobRepo.findByProducerAndIdempotencyKey("producer-1", "key-1"))
                .thenReturn(Optional.of(existing));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-1", response.getJobId());
        assertEquals("RUNNING", response.getStatus());
        assertEquals(JobPriority.HIGH.name(), response.getPriority());
        verifyNoInteractions(idempotencyService);
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldReleaseAStaleIdempotencyLockAndTakeAFreshOne() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-stale", false)), Optional.empty());
        when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).build()));
        when(jobRepo.findById("job-stale")).thenReturn(Optional.empty());
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertNotNull(response.getJobId());
        verify(idempotencyService).releaseIfOwnedBy("producer-1", "key-1", "job-stale");
    }

    @Test
    void shouldReturnTheJobThatWonTheReacquireRace() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        JobEntity raced = JobEntity.builder()
                .id("job-raced").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).nextRunAt(java.time.Instant.now()).build();
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-stale", false)),
                        Optional.of(new IdempotencyReservation("job-raced", false)));
        when(jobRepo.findById("job-stale")).thenReturn(Optional.empty());
        when(jobRepo.findById("job-raced")).thenReturn(Optional.of(raced));
        when(queueRepo.contains("job-raced", JobPriority.NORMAL)).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-raced", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldFailLoudlyWhenAReacquiredLockPointsAtNothing() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-stale", false)),
                        Optional.of(new IdempotencyReservation("job-gone", false)));
        when(jobRepo.findById(anyString())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.submit("producer-1", req));
        verify(idempotencyService).releaseIfOwnedBy("producer-1", "key-1", "job-stale");
    }

    @Test
    void shouldReturnTheInFlightJobWhenTheReservationIsStillPending() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        JobEntity inflight = JobEntity.builder()
                .id("job-inflight").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).nextRunAt(java.time.Instant.now()).build();
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-inflight", true)));
        when(jobRepo.findById("job-inflight"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(inflight));
        when(queueRepo.contains("job-inflight", JobPriority.NORMAL)).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-inflight", response.getJobId());
        verify(idempotencyService, never()).releaseIfOwnedBy(anyString(), anyString(), anyString());
        verify(idempotencyService, never()).confirmReservation(anyString(), anyString(), anyString());
    }

    @Test
    void shouldNotReturnAQueuedJobUntilItsQueueAdmissionIsVisible() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        JobEntity unqueued = JobEntity.builder()
                .id("job-unqueued").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).nextRunAt(java.time.Instant.now()).build();
        when(jobRepo.findByProducerAndIdempotencyKey("producer-1", "key-1"))
                .thenReturn(Optional.of(unqueued));
        when(jobRepo.findById("job-unqueued")).thenReturn(Optional.of(unqueued));
        when(queueRepo.contains("job-unqueued", JobPriority.NORMAL)).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.submit("producer-1", req));

        assertTrue(error.getMessage().contains("still being accepted"));
        verify(idempotencyService, never()).acquireOrGetExisting(anyString(), anyString(), anyString());
    }

    @Test
    void shouldRefuseToStealAPendingReservationWhoseJobNeverAppears() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-inflight", true)));
        when(jobRepo.findById("job-inflight")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.submit("producer-1", req));
        verify(idempotencyService, never()).releaseIfOwnedBy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReleaseTheReservationWhenPersistingTheJobFails() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        doThrow(new IllegalStateException("redis down")).when(jobRepo).save(any(JobEntity.class));

        assertThrows(IllegalStateException.class, () -> service.submit("producer-1", req));

        verify(jobRepo).delete(anyString());
        verify(idempotencyService).releaseIfOwnedBy(eq("producer-1"), eq("key-1"), anyString());
        verify(queueRepo, never()).enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void shouldLeaveAnAmbiguousQueueWriteForOrphanReconciliation() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));
        when(queueRepo.contains(anyString(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.submit("producer-1", req));
        verify(jobRepo, never()).delete(anyString());
        verify(idempotencyService, never()).releaseIfOwnedBy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldAcceptAQueueWriteThatActuallyCommitted() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("response lost"));
        when(queueRepo.contains(anyString(), any())).thenReturn(true);
        when(jobRepo.findById(anyString())).thenReturn(Optional.of(JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).build()));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo, never()).delete(anyString());
        verify(idempotencyService, never()).releaseIfOwnedBy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnAcceptedWhenThePostAdmissionVerificationReadFails() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        when(jobMapper.parsePriority(any())).thenReturn(JobPriority.NORMAL);
        when(jobMapper.serializePayload(any())).thenReturn("{}");
        when(queueRepo.enqueueIfUnderCapacity(anyString(), any(), anyLong(), anyLong())).thenReturn(true);
        when(jobRepo.findById(anyString())).thenThrow(new IllegalStateException("redis read failed"));

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("QUEUED", response.getStatus());
        verify(jobRepo, never()).delete(anyString());
        verify(idempotencyService, never()).releaseIfOwnedBy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldWaitForAReacquiredPendingReservation() {
        JobSubmissionRequest req = new JobSubmissionRequest();
        req.setJobType("test");
        req.setIdempotencyKey("key-1");
        JobEntity inflight = JobEntity.builder()
                .id("job-inflight").jobType("test").status(JobStatus.QUEUED)
                .priority(JobPriority.NORMAL).nextRunAt(java.time.Instant.now()).build();
        when(idempotencyService.acquireOrGetExisting(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new IdempotencyReservation("job-stale", false)),
                        Optional.of(new IdempotencyReservation("job-inflight", true)));
        when(jobRepo.findById("job-stale")).thenReturn(Optional.empty());
        when(jobRepo.findById("job-inflight"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(inflight));
        when(queueRepo.contains("job-inflight", JobPriority.NORMAL)).thenReturn(true);

        JobSubmissionResponse response = service.submit("producer-1", req);

        assertEquals("job-inflight", response.getJobId());
        verify(jobRepo, never()).save(any());
    }

    @Test
    void shouldGetAJobOrReportItMissing() {
        JobEntity job = JobEntity.builder().id("job-1").jobType("test").status(JobStatus.QUEUED).build();
        JobResponse mapped = mock(JobResponse.class);
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobMapper.toResponse(job)).thenReturn(mapped);

        assertSame(mapped, service.getJob("job-1"));

        when(jobRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.getJob("missing"));
    }

    @Test
    void shouldCancelAQueuedJob() {
        JobEntity job = JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED).priority(JobPriority.HIGH).build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.QUEUED, JobStatus.CANCELLED))
                .thenReturn(true);

        service.cancelJob("job-1");

        verify(queueRepo).remove("job-1", JobPriority.HIGH);
        verify(jobRepo).save(job);
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertEquals("Cancelled by user", job.getResult());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    void shouldRefuseToCancelAJobThatIsNotQueued() {
        JobEntity job = JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.RUNNING).priority(JobPriority.HIGH).build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(job));

        assertThrows(IllegalArgumentException.class, () -> service.cancelJob("job-1"));
        verify(jobRepo, never()).save(any());
        verifyNoInteractions(queueRepo);
    }

    @Test
    void shouldRefuseToCancelWhenAWorkerClaimedTheJobAfterTheRead() {
        JobEntity queued = JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.QUEUED).priority(JobPriority.HIGH).build();
        JobEntity running = JobEntity.builder()
                .id("job-1").jobType("test").status(JobStatus.RUNNING).priority(JobPriority.HIGH).build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(queued), Optional.of(running));
        when(jobRepo.transitionStatusIfCurrent("job-1", JobStatus.QUEUED, JobStatus.CANCELLED))
                .thenReturn(false);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.cancelJob("job-1"));

        assertTrue(error.getMessage().contains("RUNNING"));
        verify(jobRepo, never()).save(any());
        verifyNoInteractions(queueRepo);
    }

    @Test
    void shouldReportAMissingJobWhenCancelling() {
        when(jobRepo.findById("missing")).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> service.cancelJob("missing"));
    }
}
