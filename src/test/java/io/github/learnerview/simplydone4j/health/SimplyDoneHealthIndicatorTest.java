package io.github.learnerview.simplydone4j.health;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimplyDoneHealthIndicatorTest {

    @Mock QueueRepository queueRepo;
    @Mock JobRepository jobRepo;

    private void stubDepths(long high, long normal, long low, long dlq) {
        when(queueRepo.queueSize(JobPriority.HIGH)).thenReturn(high);
        when(queueRepo.queueSize(JobPriority.NORMAL)).thenReturn(normal);
        when(queueRepo.queueSize(JobPriority.LOW)).thenReturn(low);
        when(jobRepo.countByStatus(JobStatus.DLQ)).thenReturn(dlq);
        when(jobRepo.countByStatus(JobStatus.RUNNING)).thenReturn(4L);
    }

    @Test
    void shouldBeUpWhileTheEngineIsDrainingNormally() {
        stubDepths(10, 5, 1, 0);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 10000L).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(16L, health.getDetails().get("totalQueued"));
        assertEquals(4L, health.getDetails().get("running"));
    }

    @Test
    void shouldGoDownOnceAnythingReachesTheDeadLetterState() {
        stubDepths(0, 0, 0, 3);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 10000L).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(3L, health.getDetails().get("deadLettered"));
    }

    @Test
    void shouldGoDownWhenTheBacklogExceedsTheConfiguredCeiling() {
        stubDepths(20000, 0, 0, 0);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 10000L).health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void aZeroDeadLetterThresholdDisablesTheDeadLetterCheck() {
        // 0 means "off" for both thresholds. Taken literally, deadLettered >= 0 is
        // always true, so honouring the documented contract requires an explicit
        // guard; otherwise setting 0 pins the application to DOWN permanently.
        stubDepths(0, 0, 0, 5);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 0L, 10000L).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(5L, health.getDetails().get("deadLettered"));
    }

    @Test
    void aZeroMaxQueueDepthDisablesTheDepthCheck() {
        stubDepths(20000, 0, 0, 0);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 0L).health();

        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void bothChecksDisabledStaysUpEvenWhenBothAreBreached() {
        stubDepths(20000, 20000, 20000, 7);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 0L, 0L).health();

        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void theDepthCheckAppliesToTheSumAcrossQueues() {
        // Documented as a total, not "any single queue": 6k in each is 18k overall.
        stubDepths(6000, 6000, 6000, 0);

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 10000L).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(18000L, health.getDetails().get("totalQueued"));
    }

    @Test
    void shouldReportDownWithTheCauseWhenTheStoreIsUnreachable() {
        when(queueRepo.queueSize(JobPriority.HIGH)).thenThrow(new RuntimeException("redis down"));

        Health health = new SimplyDoneHealthIndicator(queueRepo, jobRepo, 1L, 10000L).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("java.lang.RuntimeException: redis down", health.getDetails().get("error"));
    }
}
