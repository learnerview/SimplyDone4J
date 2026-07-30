package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
    void save(JobEntity job);
    Optional<JobEntity> findById(String jobId);
    Optional<JobEntity> findByProducerAndIdempotencyKey(String producer, String idempotencyKey);
    List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit);
    long countByStatus(JobStatus status);
    long countByStatusAndPriority(JobStatus status, JobPriority priority);
    int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil, Instant now, JobStatus fromStatus, JobStatus toStatus);
    List<JobEntity> findByProducerAndStatus(String producer, JobStatus status);
    List<JobEntity> findByStatus(JobStatus status);
}
