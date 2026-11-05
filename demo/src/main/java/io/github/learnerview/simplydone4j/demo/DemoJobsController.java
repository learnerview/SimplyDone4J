package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoJobsController {

    private final DemoJobService jobs;
    private final EngineStatusService engineStatus;
    private final HandlerRegistry handlers;

    public DemoJobsController(DemoJobService jobs, EngineStatusService engineStatus,
                              HandlerRegistry handlers) {
        this.jobs = jobs;
        this.engineStatus = engineStatus;
        this.handlers = handlers;
    }

    @GetMapping("/jobs")
    public DemoJobService.JobPage jobs(
            @RequestParam(name = "status", defaultValue = "ALL") String status,
            @RequestParam(name = "priority", defaultValue = "ALL") String priority,
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return jobs.jobs(status, priority, query, limit);
    }

    @GetMapping("/handlers")
    public List<String> handlers() {
        return handlers.getHandlers().keySet().stream().sorted().toList();
    }

    @GetMapping("/engine")
    public EngineStatus engine() {
        return engineStatus.status();
    }

    @GetMapping("/dlq")
    public DemoJobService.DeadLetterPage deadLetters(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return jobs.deadLetters(query, limit);
    }

    @PostMapping("/dlq/{jobId}/replay")
    public JobResponse replay(@PathVariable String jobId) {
        return jobs.replay(jobId);
    }

    @DeleteMapping("/dlq/{jobId}")
    public Map<String, Object> discard(@PathVariable String jobId) {
        jobs.discard(jobId);
        return Map.of("discarded", jobId, "generatedAt", Instant.now());
    }
}
