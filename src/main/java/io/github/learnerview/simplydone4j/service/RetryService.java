package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;

public interface RetryService {
    String handleFailure(JobEntity job, String errorMessage, long durationMs);
    void logSuccess(JobEntity job, String message, long durationMs);
}
