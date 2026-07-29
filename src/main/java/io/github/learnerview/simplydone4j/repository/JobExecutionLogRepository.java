package io.github.learnerview.simplydone4j.repository;

import io.github.learnerview.simplydone4j.entity.JobExecutionLog;

import java.util.List;

public interface JobExecutionLogRepository {
    void save(JobExecutionLog log);
    List<JobExecutionLog> findByJobIdOrderByAttemptAsc(String jobId);
}
