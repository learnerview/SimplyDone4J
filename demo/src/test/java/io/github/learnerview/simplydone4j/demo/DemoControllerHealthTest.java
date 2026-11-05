package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoControllerHealthTest {

    @Mock JobSubmissionService submissionService;
    @Mock JobQueryRepository jobs;
    @Mock JobExecutionLogRepository logs;
    @Mock DemoScenarioService scenarios;
    @Mock DemoEventLog events;
    @Mock EngineStatusService engineStatus;

    DemoController controller;

    @BeforeEach
    void setUp() {
        controller = new DemoController(submissionService, jobs, logs, scenarios, events, engineStatus);
    }

    @Test
    void shouldReturnOkWhenRedisAndLoopsAreHealthy() {
        when(engineStatus.status()).thenReturn(new EngineStatus(
                true, true, true, true, 1, 4, Map.of(JobPriority.HIGH, 2L)));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("redis"));
        assertEquals(1, response.getBody().get("workersInFlight"));
    }

    @Test
    void shouldReturnServiceUnavailableWhenRedisIsDown() {
        when(engineStatus.status()).thenReturn(new EngineStatus(
                false, true, true, true, 0, 4, Map.of()));

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("redis"));
        assertTrue(response.getBody().containsKey("generatedAt"));
    }
}
