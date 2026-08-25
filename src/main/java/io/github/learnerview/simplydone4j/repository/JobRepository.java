package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JobWorkerRepository, JobQueryRepository {
    void save(JobEntity job);
    Optional<JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey);
}
