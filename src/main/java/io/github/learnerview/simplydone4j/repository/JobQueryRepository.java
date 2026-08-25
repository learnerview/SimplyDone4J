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
    long countByStatus(JobStatus status);
    long countByStatusAndPriority(JobStatus status, JobPriority priority);
}
