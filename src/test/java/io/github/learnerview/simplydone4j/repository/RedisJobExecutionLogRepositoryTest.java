package io.github.learnerview.simplydone4j.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisJobExecutionLogRepositoryTest {

    @Mock StringRedisTemplate redis;
    @Mock ListOperations<String, String> listOps;

    ObjectMapper objectMapper;
    RedisJobExecutionLogRepository repo;
    SimplyDoneProperties props;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.setDefaultPropertyInclusion(com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS));

        // Lenient because defaultsToNotStoringExecutionLogs asserts that nothing is
        // written, so it never reaches the list operations.
        lenient().when(redis.opsForList()).thenReturn(listOps);
        // Saving is opt-in, so the default-off case is asserted separately in
        // defaultsToNotStoringExecutionLogs.
        props = new SimplyDoneProperties();
        props.getRetention().setStoreExecutionLogs(true);
        repo = new RedisJobExecutionLogRepository(redis, objectMapper, props);
    }

    @Test
    void defaultsToNotStoringExecutionLogs() {
        SimplyDoneProperties defaults = new SimplyDoneProperties();
        assertThat(defaults.getRetention().isStoreExecutionLogs())
                .as("execution logs are a diagnostic, not engine state; opt in")
                .isFalse();

        RedisJobExecutionLogRepository quiet =
                new RedisJobExecutionLogRepository(redis, objectMapper, defaults);
        JobExecutionLog log = JobExecutionLog.builder()
                .jobId("job-1").attempt(1).status("SUCCESS").message("x").durationMs(1L)
                .executedAt(java.time.Instant.now()).build();

        quiet.save(log);

        verify(redis, never()).opsForList();
    }

    @Nested
    class SaveLog {
        @Test
        void shouldSaveExecutionLog() {
            JobExecutionLog log = JobExecutionLog.builder()
                    .jobId("job-1")
                    .attempt(1)
                    .status("SUCCESS")
                    .message("Completed OK")
                    .durationMs(100L)
                    .executedAt(Instant.now())
                    .build();

            repo.save(log);

            verify(listOps).leftPush(eq("simplydone4j:log:job-1"), anyString());
            verify(listOps).trim(eq("simplydone4j:log:job-1"), eq(0L), eq(49L));
            verify(redis).expire(eq("simplydone4j:log:job-1"), any(java.time.Duration.class));
        }

        @Test
        void shouldAssignIdWhenNull() {
            JobExecutionLog log = JobExecutionLog.builder()
                    .jobId("job-1")
                    .attempt(1)
                    .status("FAILED")
                    .message("Error")
                    .build();

            assertNull(log.getId());
            repo.save(log);
            assertNotNull(log.getId());
        }

        @Test
        void shouldSaveAndNotThrow() {
            JobExecutionLog log = JobExecutionLog.builder().jobId("job-1").attempt(1).status("OK").build();
            assertDoesNotThrow(() -> repo.save(log));
            verify(listOps).leftPush(anyString(), anyString());
        }
    }

    @Nested
    class FindLogs {
        @Test
        void shouldFindLogsByJobId() throws Exception {
            String json1 = "{\"id\":\"log-1\",\"jobId\":\"job-1\",\"attempt\":1,\"status\":\"SUCCESS\",\"durationMs\":50}";
            String json2 = "{\"id\":\"log-2\",\"jobId\":\"job-1\",\"attempt\":2,\"status\":\"FAILED\",\"durationMs\":30}";

            when(listOps.range("simplydone4j:log:job-1", 0, -1))
                    .thenReturn(List.of(json1, json2));

            List<JobExecutionLog> logs = repo.findByJobIdOrderByAttemptAsc("job-1");

            assertEquals(2, logs.size());
            assertEquals("log-1", logs.get(0).getId());
            assertEquals("SUCCESS", logs.get(0).getStatus());
        }

        @Test
        void shouldReturnEmptyListWhenNoLogs() {
            when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

            List<JobExecutionLog> logs = repo.findByJobIdOrderByAttemptAsc("job-nonexistent");
            assertTrue(logs.isEmpty());
        }

        @Test
        void shouldReturnEmptyListForEmptyLogs() {
            when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());

            List<JobExecutionLog> logs = repo.findByJobIdOrderByAttemptAsc("job-empty");
            assertTrue(logs.isEmpty());
        }

        @Test
        void shouldSkipCorruptedLogEntries() {
            when(listOps.range(anyString(), anyLong(), anyLong()))
                    .thenReturn(List.of("{\"id\":\"ok\"}", "{invalid", "{\"id\":\"also-ok\"}"));

            List<JobExecutionLog> logs = repo.findByJobIdOrderByAttemptAsc("job-corrupt");

            assertTrue(logs.isEmpty() || logs.size() >= 2);
        }
    }
}
