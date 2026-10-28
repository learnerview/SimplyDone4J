package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.api.spi.JacksonPayloadCodec;
import io.github.learnerview.simplydone4j.api.spi.PayloadCodec;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.CancelOutcome;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultJobClientTest {

    @Mock private JobSubmissionService submissionService;
    @Mock private JobRepository jobRepo;
    @Mock private QueueRepository queueRepo;
    @Mock private DeadLetterService deadLetterService;
    @Mock private JobEventPublisher eventPublisher;

    private ObjectMapper objectMapper;
    private JobMapper jobMapper;
    private PayloadCodec payloadCodec;
    private DefaultJobClient client;

    record PaymentEvent(String invoiceId, double amount) {}

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        jobMapper = new JobMapper(objectMapper);
        payloadCodec = new JacksonPayloadCodec(objectMapper);
        client = new DefaultJobClient(submissionService, jobRepo, queueRepo, deadLetterService,
                jobMapper, payloadCodec, eventPublisher);
    }

    @Test
    void shouldSubmitJobAndReturnHandle() {
        PaymentEvent event = new PaymentEvent("inv-101", 149.99);
        JobSubmissionResponse serviceRes = JobSubmissionResponse.builder()
                .jobId("job-777")
                .jobType("payment.process")
                .status("QUEUED")
                .build();

        ArgumentCaptor<JobSubmissionRequest> captor = ArgumentCaptor.forClass(JobSubmissionRequest.class);
        when(submissionService.submit(eq("billing"), captor.capture())).thenReturn(serviceRes);

        JobHandle handle = client.submit("payment.process", event, opts -> opts
                .producer("billing")
                .idempotencyKey("idemp-777")
                .priority(JobPriority.HIGH)
                .delay(Duration.ofSeconds(30))
                .maxAttempts(4)
                .timeout(Duration.ofSeconds(45)));

        assertNotNull(handle);
        assertEquals("job-777", handle.id());
        assertEquals("payment.process", handle.jobType());
        assertEquals("QUEUED", handle.status());

        JobSubmissionRequest captured = captor.getValue();
        assertEquals("payment.process", captured.getJobType());
        assertEquals("idemp-777", captured.getIdempotencyKey());
        assertEquals("HIGH", captured.getPriority());
        assertEquals(4, captured.getMaxAttempts());
        assertEquals(45, captured.getTimeoutSeconds());
        assertEquals("inv-101", captured.getPayload().get("invoiceId"));
        assertEquals(149.99, (Double) captured.getPayload().get("amount"), 0.001);
    }

    @Test
    void shouldGetJobAndConvertTypedPayload() {
        JobEntity entity = JobEntity.builder()
                .id("job-123")
                .jobType("payment.process")
                .producer("billing")
                .status(JobStatus.SUCCESS)
                .priority(JobPriority.NORMAL)
                .payload("{\"invoiceId\":\"inv-555\",\"amount\":99.50}")
                .result("Charged successfully")
                .progress(100.0)
                .progressMessage("Done")
                .attemptCount(1)
                .maxAttempts(3)
                .createdAt(Instant.parse("2026-03-01T10:00:00Z"))
                .completedAt(Instant.parse("2026-03-01T10:00:02Z"))
                .build();

        when(jobRepo.findById("job-123")).thenReturn(Optional.of(entity));

        Optional<JobView> viewOpt = client.get("job-123");
        assertTrue(viewOpt.isPresent());

        JobView view = viewOpt.get();
        assertEquals("job-123", view.id());
        assertEquals(JobStatus.SUCCESS, view.status());
        assertEquals("Charged successfully", view.result());
        assertEquals(100.0, view.progress());
        assertEquals(1, view.attemptCount());

        PaymentEvent typed = view.payloadAs(PaymentEvent.class);
        assertNotNull(typed);
        assertEquals("inv-555", typed.invoiceId());
        assertEquals(99.50, typed.amount(), 0.001);
    }

    @Test
    void shouldHandleCancelScenarios() {
        when(jobRepo.requestCancellation("job-1", "user")).thenReturn(CancelOutcome.CANCELLED);
        JobEntity waiting = JobEntity.builder().id("job-1").status(JobStatus.CANCELLED).priority(JobPriority.HIGH).build();
        when(jobRepo.findById("job-1")).thenReturn(Optional.of(waiting));

        CancelOutcome outcome = client.cancel("job-1");
        assertEquals(CancelOutcome.CANCELLED, outcome);
        verify(queueRepo).remove("job-1", JobPriority.HIGH);
        verify(eventPublisher).publish(eq(JobEvent.JOB_CANCELLED), any());

        when(jobRepo.requestCancellation("job-2", "user")).thenReturn(CancelOutcome.REQUESTED);
        assertEquals(CancelOutcome.REQUESTED, client.cancel("job-2"));

        assertEquals(CancelOutcome.NOT_FOUND, client.cancel(null));
    }

    @Test
    void shouldRetryDeadLetteredOrFailedJob() {
        JobEntity dlqJob = JobEntity.builder().id("job-dlq").build();
        when(deadLetterService.requeue("job-dlq")).thenReturn(dlqJob);

        assertTrue(client.retry("job-dlq"));
        verify(deadLetterService).requeue("job-dlq");

        when(deadLetterService.requeue("job-fail")).thenThrow(new IllegalStateException("Not in DLQ"));
        assertFalse(client.retry("job-fail"));
        assertFalse(client.retry(""));
    }

    @Test
    void shouldListAndCountByStatus() {
        JobEntity a = JobEntity.builder().id("a").jobType("t").status(JobStatus.QUEUED).build();
        JobEntity b = JobEntity.builder().id("b").jobType("t").status(JobStatus.QUEUED).build();
        when(jobRepo.findByStatus(JobStatus.QUEUED)).thenReturn(List.of(a, b));
        when(jobRepo.countByStatus(JobStatus.QUEUED)).thenReturn(2L);

        List<JobView> views = client.listByStatus(JobStatus.QUEUED);
        assertEquals(2, views.size());
        assertEquals("a", views.get(0).id());
        assertEquals("b", views.get(1).id());

        assertEquals(2L, client.countByStatus(JobStatus.QUEUED));
        assertEquals(0L, client.countByStatus(null));
        assertTrue(client.listByStatus(null).isEmpty());
    }
}
