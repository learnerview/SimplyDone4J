package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;

public interface WebhookService {
    void fireCallback(JobEntity job, String outcome, String errorMessage);
}
