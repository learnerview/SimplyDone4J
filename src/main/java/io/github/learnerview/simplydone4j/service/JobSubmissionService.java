package io.github.learnerview.simplydone4j.service;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;

public interface JobSubmissionService {
    JobSubmissionResponse submit(String producer, JobSubmissionRequest request);
    JobResponse getJob(String jobId);
    void cancelJob(String jobId);
}
