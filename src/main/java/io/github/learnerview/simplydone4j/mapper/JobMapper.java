package io.github.learnerview.simplydone4j.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
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
                .nextRunAt(job.getNextRunAt())
                .visibleAt(job.getVisibleAt())
                .leaseOwner(job.getLeaseOwner())
                .executionType(job.getExecutionType())
                .executionEndpoint(job.getExecutionEndpoint())
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> deserializePayload(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize payload: {}", e.getMessage());
            return Map.of();
        }
    }

    public String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid payload: " + e.getMessage());
        }
    }

    public JobPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) return JobPriority.NORMAL;
        try {
            return JobPriority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            return JobPriority.NORMAL;
        }
    }
}
