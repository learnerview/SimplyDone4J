package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobStatus;

import java.time.Instant;
import java.util.List;

public interface JobWorkerRepository {
    List<JobEntity> findReadyToRun(JobStatus status, Instant before, int limit);
    int claimForExecution(String jobId, String leaseToken, String workerId, Instant visibleUntil, Instant now, JobStatus fromStatus, JobStatus toStatus);
}
