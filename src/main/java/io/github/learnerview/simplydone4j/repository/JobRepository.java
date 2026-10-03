package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;


import java.util.Optional;

public interface JobRepository extends JobWorkerRepository, JobQueryRepository {
    void save(JobEntity job);
    Optional<JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey);
}
