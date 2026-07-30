package io.github.learnerview.simplydone4j.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobMapperTest {

    JobMapper mapper;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper = new JobMapper(objectMapper);
    }

    @Nested
    class PayloadSerialization {
        @Test
        void shouldSerializePayload() {
            Map<String, Object> payload = Map.of("key1", "value1", "key2", 42);
            String result = mapper.serializePayload(payload);
            assertNotNull(result);
            assertTrue(result.contains("key1"));
            assertTrue(result.contains("value1"));
        }

        @Test
        void shouldReturnNullForNullPayload() {
            assertNull(mapper.serializePayload(null));
        }

        @Test
        void shouldReturnNullForEmptyPayload() {
            assertNull(mapper.serializePayload(Map.of()));
        }

        @Test
        void shouldThrowForUnserializablePayload() {
            Object badValue = new Object() {
                public String toString() { return "bad"; }
            };
            assertThrows(IllegalArgumentException.class,
                    () -> mapper.serializePayload(Map.of("bad", badValue)));
        }
    }

    @Nested
    class PayloadDeserialization {
        @Test
        void shouldDeserializeValidJson() {
            Map<String, Object> result = mapper.deserializePayload("{\"name\":\"test\",\"count\":5}");
            assertEquals("test", result.get("name"));
            assertEquals(5, result.get("count"));
        }

        @Test
        void shouldReturnEmptyMapForNullPayload() {
            assertEquals(Map.of(), mapper.deserializePayload(null));
        }

        @Test
        void shouldReturnEmptyMapForBlankPayload() {
            assertEquals(Map.of(), mapper.deserializePayload(""));
            assertEquals(Map.of(), mapper.deserializePayload("   "));
        }

        @Test
        void shouldReturnEmptyMapForInvalidJson() {
            Map<String, Object> result = mapper.deserializePayload("not-json");
            assertEquals(Map.of(), result);
        }
    }

    @Nested
    class PriorityParsing {
        @Test
        void shouldParseHighPriority() {
            assertEquals(JobPriority.HIGH, mapper.parsePriority("HIGH"));
            assertEquals(JobPriority.HIGH, mapper.parsePriority("high"));
            assertEquals(JobPriority.HIGH, mapper.parsePriority("High"));
        }

        @Test
        void shouldParseNormalPriority() {
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("NORMAL"));
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("normal"));
        }

        @Test
        void shouldParseLowPriority() {
            assertEquals(JobPriority.LOW, mapper.parsePriority("LOW"));
            assertEquals(JobPriority.LOW, mapper.parsePriority("low"));
        }

        @Test
        void shouldDefaultToNormalForNullPriority() {
            assertEquals(JobPriority.NORMAL, mapper.parsePriority(null));
        }

        @Test
        void shouldDefaultToNormalForBlankPriority() {
            assertEquals(JobPriority.NORMAL, mapper.parsePriority(""));
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("   "));
        }

        @Test
        void shouldDefaultToNormalForInvalidPriority() {
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("URGENT"));
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("CRITICAL"));
        }

        @Test
        void shouldDefaultToNormalForMalformedPriority() {
            assertEquals(JobPriority.NORMAL, mapper.parsePriority("123"));
        }
    }

    @Nested
    class EntityToResponseMapping {
        @Test
        void shouldMapAllFields() {
            Instant now = Instant.now();
            JobEntity entity = JobEntity.builder()
                    .id("job-1")
                    .jobType("email")
                    .producer("my-app")
                    .idempotencyKey("key-1")
                    .status(JobStatus.SUCCESS)
                    .priority(JobPriority.HIGH)
                    .payload("{\"to\":\"user@test.com\"}")
                    .result("Sent OK")
                    .nextRunAt(now)
                    .leaseOwner("worker-1")
                    .timeoutSeconds(30)
                    .callbackUrl("https://example.com/cb")
                    .startedAt(now)
                    .completedAt(now)
                    .attemptCount(1)
                    .maxAttempts(3)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            JobResponse response = mapper.toResponse(entity);

            assertEquals("job-1", response.getId());
            assertEquals("email", response.getJobType());
            assertEquals("my-app", response.getProducer());
            assertEquals("key-1", response.getIdempotencyKey());
            assertEquals("SUCCESS", response.getStatus());
            assertEquals("HIGH", response.getPriority());
            assertEquals("Sent OK", response.getResult());
            assertEquals("worker-1", response.getLeaseOwner());
            assertEquals(30, response.getTimeoutSeconds());
            assertEquals("https://example.com/cb", response.getCallbackUrl());
            assertEquals(1, response.getAttemptCount());
            assertEquals(3, response.getMaxAttempts());
            assertEquals(Map.of("to", "user@test.com"), response.getPayload());
        }

        @Test
        void shouldHandleNullFields() {
            JobEntity entity = JobEntity.builder()
                    .id("job-1")
                    .jobType("test")
                    .producer("app")
                    .status(JobStatus.QUEUED)
                    .priority(JobPriority.NORMAL)
                    .build();

            JobResponse response = mapper.toResponse(entity);

            assertEquals("job-1", response.getId());
            assertNull(response.getResult());
            assertNull(response.getCallbackUrl());
            assertNull(response.getLeaseOwner());
            assertNull(response.getTimeoutSeconds());
            assertEquals(Map.of(), response.getPayload());
        }
    }
}
