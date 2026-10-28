package io.github.learnerview.simplydone4j.mapper;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.exception.InvalidPayloadException;
import io.github.learnerview.simplydone4j.model.JobPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class JobMapper {
    private static final Logger log = LoggerFactory.getLogger(JobMapper.class);
    private final ObjectMapper objectMapper;

    public JobMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JobResponse toResponse(JobEntity job) {
        return JobResponse.builder()
                .id(job.getId())
                .jobType(job.getJobType())
                .producer(job.getProducer())
                .idempotencyKey(job.getIdempotencyKey())
                .status(job.getStatus().name())
                .priority(job.getPriority().name())
                .payload(deserializePayload(job.getPayload()))
                .result(job.getResult())
                .errorCode(job.getErrorCode())
                .progress(job.getProgress())
                .progressMessage(job.getProgressMessage())
                .cancelRequested(job.getCancelRequested())
                .cancelledBy(job.getCancelledBy())
                .nextRunAt(job.getNextRunAt())
                .visibleAt(job.getVisibleAt())
                .leaseOwner(job.getLeaseOwner())
                .timeoutSeconds(job.getTimeoutSeconds())
                .callbackUrl(job.getCallbackUrl())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .attemptCount(job.getAttemptCount())
                .maxAttempts(job.getMaxAttempts())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    public Map<String, Object> deserializePayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(
                payload,
                new TypeReference<Map<String, Object>>() {}
            );
        } catch (JacksonException e) {
            log.warn("Failed to deserialize payload", e);
            return Map.of();
        }
    }

    public String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // A typed exception, so the caller can tell "your payload is broken" from
            // "the infrastructure is broken" without reading the message.
            throw new InvalidPayloadException("Invalid payload: " + e.getMessage(), e);
        }
    }

    public JobPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) return JobPriority.NORMAL;
        try {
            return JobPriority.valueOf(priority.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return JobPriority.NORMAL;
        }
    }
}
