package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.entity.JobEntity;

@FunctionalInterface
public interface JobExecutorService {
    void execute(JobEntity job);
}
