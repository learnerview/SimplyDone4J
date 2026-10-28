package io.github.learnerview.simplydone4j.api.spi;

import io.github.learnerview.simplydone4j.model.JobPriority;

import java.time.Instant;
import java.util.Map;

/**
 * What the library knows about a submission when it asks the policy whether to admit it.
 *
 * @param producer    who submitted the job
 * @param jobType     the handler's job type
 * @param priority    the requested priority
 * @param payload     the submitted payload
 * @param submittedAt when the submission arrived
 */
public record AdmissionRequest(
        String producer,
        String jobType,
        JobPriority priority,
        Map<String, Object> payload,
        Instant submittedAt) {
}
