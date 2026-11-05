package io.github.learnerview.simplydone4j.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.mapper.JobMapper;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.service.DeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoJobServiceTest {

    @Mock JobQueryRepository repository;
    @Mock DeadLetterService deadLetterService;

    DemoJobService jobs;

    @BeforeEach
    void setUp() {
        jobs = new DemoJobService(repository, new JobMapper(new ObjectMapper()), deadLetterService);
    }

    @Test
    void shouldFilterJobsByStatusPriorityAndText() {
        JobEntity payment = job("job-1", "charge-payment", JobStatus.QUEUED,
                JobPriority.HIGH, "checkout", "gateway timeout");
        JobEntity packing = job("job-2", "process-order", JobStatus.QUEUED,
                JobPriority.NORMAL, "checkout", "packing");
        when(repository.findByStatus(JobStatus.QUEUED, 200)).thenReturn(List.of(payment, packing));

        DemoJobService.JobPage page = jobs.jobs("QUEUED", "HIGH", "payment", 50);

        assertEquals(1, page.matched());
        assertEquals("job-1", page.jobs().getFirst().getId());
        assertEquals(50, page.limit());
    }

    @Test
    void shouldReturnNoMatchesWhenSearchDoesNotMatch() {
        when(repository.findByStatus(JobStatus.SUCCESS, 200))
                .thenReturn(List.of(job("job-3", "process-order", JobStatus.SUCCESS,
                        JobPriority.NORMAL, "checkout", "done")));

        DemoJobService.JobPage page = jobs.jobs("SUCCESS", "ALL", "missing-text", 10);

        assertEquals(0, page.matched());
        assertTrue(page.jobs().isEmpty());
    }

    @Test
    void shouldClampThePageSize() {
        when(repository.findByStatus(JobStatus.QUEUED, 200)).thenReturn(List.of());

        DemoJobService.JobPage page = jobs.jobs("QUEUED", "ALL", "", 5_000);

        assertEquals(200, page.limit());
    }

    @Test
    void shouldListDeadLettersWithTheTotalCount() {
        JobResponse failed = JobResponse.builder()
                .id("job-4")
                .jobType("charge-payment")
                .status("DLQ")
                .priority("HIGH")
                .producer("checkout")
                .result("provider down")
                .maxAttempts(3)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(deadLetterService.list(100)).thenReturn(List.of(failed));
        when(deadLetterService.count()).thenReturn(7L);

        DemoJobService.DeadLetterPage page = jobs.deadLetters("provider", 100);

        assertEquals(1, page.jobs().size());
        assertEquals(7L, page.total());
    }

    @Test
    void shouldDelegateReplayAndDiscard() {
        JobResponse response = JobResponse.builder().id("job-5").status("QUEUED").build();
        when(deadLetterService.replay("job-5")).thenReturn(response);

        assertSame(response, jobs.replay("job-5"));
        jobs.discard("job-6");

        verify(deadLetterService).replay("job-5");
        verify(deadLetterService).discard("job-6");
    }

    private JobEntity job(String id, String type, JobStatus status, JobPriority priority,
                          String producer, String result) {
        return JobEntity.builder()
                .id(id)
                .jobType(type)
                .status(status)
                .priority(priority)
                .producer(producer)
                .result(result)
                .payload("{}")
                .attemptCount(1)
                .maxAttempts(3)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
