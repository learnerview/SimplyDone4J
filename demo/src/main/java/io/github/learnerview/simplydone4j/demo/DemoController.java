package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.entity.JobExecutionLog;
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobExecutionLogRepository;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {

    private final JobSubmissionService submissionService;
    private final JobQueryRepository jobQueryRepository;
    private final JobExecutionLogRepository logRepository;
    private final DemoScenarioService scenarioService;
    private final DemoEventLog eventLog;
    private final EngineStatusService engineStatus;

    public DemoController(JobSubmissionService submissionService,
                          JobQueryRepository jobQueryRepository,
                          JobExecutionLogRepository logRepository,
                          DemoScenarioService scenarioService,
                          DemoEventLog eventLog,
                          EngineStatusService engineStatus) {
        this.submissionService = submissionService;
        this.jobQueryRepository = jobQueryRepository;
        this.logRepository = logRepository;
        this.scenarioService = scenarioService;
        this.eventLog = eventLog;
        this.engineStatus = engineStatus;
    }

    @PostMapping("/jobs")
    public JobSubmissionResponse submit(
            @RequestHeader(name = "X-Producer", defaultValue = "demo-ui") String producer,
            @Valid @RequestBody JobSubmissionRequest request) {
        return submissionService.submit(producer, request);
    }

    @GetMapping("/jobs/{id}")
    public JobResponse getJob(@PathVariable String id) {
        return submissionService.getJob(id);
    }

    @GetMapping("/jobs/{id}/logs")
    public List<JobExecutionLog> logs(@PathVariable String id) {
        return logRepository.findByJobIdOrderByAttemptAsc(id);
    }

    @DeleteMapping("/jobs/{id}")
    public JobResponse cancel(@PathVariable String id) {
        submissionService.cancelJob(id);
        return submissionService.getJob(id);
    }

    @GetMapping("/scenarios")
    public List<DemoScenarioService.ScenarioInfo> scenarios() {
        return scenarioService.scenarios();
    }

    @PostMapping("/scenarios/{name}")
    public DemoScenarioService.ScenarioResult runScenario(@PathVariable String name) {
        return scenarioService.run(name);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        EngineStatus engine = engineStatus.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", engine.healthy() ? "UP" : "DEGRADED");
        body.put("redis", engine.redisReachable() ? "UP" : "DOWN");
        body.put("schedulerEnabled", engine.schedulerEnabled());
        body.put("schedulerRunning", engine.schedulerRunning());
        body.put("maintenanceRunning", engine.maintenanceRunning());
        body.put("workersInFlight", engine.workersInFlight());
        body.put("workerCapacity", engine.workerCapacity());
        body.put("queueDepths", engine.queueDepths());
        body.put("generatedAt", Instant.now());
        return ResponseEntity.status(engine.healthy()
                ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return new Dashboard(recentJobs(), eventLog.recent(), Instant.now());
    }

    private List<JobEntity> recentJobs() {
        List<JobEntity> jobs = new ArrayList<>();
        for (JobStatus status : JobStatus.values()) {
            jobs.addAll(jobQueryRepository.findByStatus(status));
        }
        jobs.sort(Comparator.comparing(JobEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return jobs.stream().limit(100).toList();
    }

    public record Dashboard(List<JobEntity> jobs,
                            List<DemoEventLog.Event> events,
                            Instant generatedAt) {
    }
}
