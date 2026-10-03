package io.github.learnerview.simplydone4j.health;

import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator health for the job engine.
 *
 * <p>Reports {@code UP} while the engine is draining normally and {@code DOWN} once a
 * configured threshold is crossed. The two signals that matter operationally are
 * <em>jobs parked in the dead-letter state</em> and <em>total queued depth above the
 * configured ceiling</em>: both mean work is no longer moving and neither shows up in a
 * process liveness check. The depth check is on the <em>sum</em> across all three
 * priority queues, since a backlog spread evenly across them is just as stuck.</p>
 *
 * <p>A threshold of {@code 0} disables that individual check, so
 * {@code dead-letter-threshold: 0} reports DLQ pressure without alerting on queue depth
 * and vice versa.</p>
 *
 * <p>A repository failure reports {@code DOWN} with the exception recorded rather than
 * propagating, so a Redis outage shows up as an unhealthy engine instead of a 500 from
 * the health endpoint.</p>
 */
public final class SimplyDoneHealthIndicator implements HealthIndicator {

    private final QueueRepository queueRepo;
    private final JobRepository jobRepo;
    private final long deadLetterThreshold;
    private final long maxQueueDepth;

    public SimplyDoneHealthIndicator(QueueRepository queueRepo, JobRepository jobRepo,
                                    long deadLetterThreshold, long maxQueueDepth) {
        this.queueRepo = queueRepo;
        this.jobRepo = jobRepo;
        this.deadLetterThreshold = deadLetterThreshold;
        this.maxQueueDepth = maxQueueDepth;
    }

    @Override
    public Health health() {
        try {
            Map<String, Long> queueDepths = new LinkedHashMap<>();
            long totalQueued = 0L;
            for (JobPriority priority : JobPriority.values()) {
                long size = queueRepo.queueSize(priority);
                queueDepths.put(priority.name(), size);
                totalQueued += size;
            }

            long deadLettered = jobRepo.countByStatus(JobStatus.DLQ);
            long running = jobRepo.countByStatus(JobStatus.RUNNING);

            // A threshold of 0 disables that check. Taken literally, deadLettered >= 0 is
            // always true and totalQueued > 0 is true whenever anything is queued, so
            // without this guard "0" would pin the whole application to DOWN instead of
            // turning the check off.
            boolean deadLetterBreached = deadLetterThreshold > 0 && deadLettered >= deadLetterThreshold;
            boolean queueDepthBreached = maxQueueDepth > 0 && totalQueued > maxQueueDepth;

            Health.Builder builder = (deadLetterBreached || queueDepthBreached)
                    ? Health.down()
                    : Health.up();

            return builder
                    .withDetail("queueDepths", queueDepths)
                    .withDetail("totalQueued", totalQueued)
                    .withDetail("running", running)
                    .withDetail("deadLettered", deadLettered)
                    .withDetail("deadLetterThreshold", deadLetterThreshold)
                    .withDetail("maxQueueDepth", maxQueueDepth)
                    .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
