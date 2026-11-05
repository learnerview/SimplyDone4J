package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.util.List;
import java.util.Optional;

public interface JobQueryRepository {
    Optional<JobEntity> findById(String jobId);
    List<JobEntity> findByProducerAndStatus(String producer, JobStatus status);
    List<JobEntity> findByStatus(JobStatus status);

    /**
     * Returns at most {@code limit} jobs in the given status, oldest first.
     *
     * <p>Defaulted for source compatibility with custom repositories. Implementations backed
     * by an index should override this so a large DLQ can be paged without materialising
     * every job record.</p>
     */
    default List<JobEntity> findByStatus(JobStatus status, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<JobEntity> jobs = findByStatus(status);
        return jobs.size() <= limit ? jobs : List.copyOf(jobs.subList(0, limit));
    }

    long countByStatus(JobStatus status);
    long countByStatusAndPriority(JobStatus status, JobPriority priority);
}
