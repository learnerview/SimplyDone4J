package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DemoScenarioService {

    private final JobSubmissionService submissionService;
    private final SimplyDoneProperties properties;
    public DemoScenarioService(JobSubmissionService submissionService,
                               SimplyDoneProperties properties) {
        this.submissionService = submissionService;
        this.properties = properties;
    }

    public List<ScenarioInfo> scenarios() {
        return List.of(
                new ScenarioInfo("success", "Happy path",
                        "Accept an online order and watch the fulfillment job complete."),
                new ScenarioInfo("idempotency", "Idempotency",
                        "Double-click an order submission; the same order is queued only once."),
                new ScenarioInfo("priority", "Priority lanes",
                        "Queue premium, standard, and economy fulfillment using weighted service lanes."),
                new ScenarioInfo("delay", "Delayed execution",
                        "Schedule an order fulfillment for later and inspect its next run time."),
                new ScenarioInfo("retry", "Retry with backoff",
                        "A payment gateway blip is retried with exponential backoff, then succeeds."),
                new ScenarioInfo("dlq", "Dead-letter queue and replay",
                        "A provider outage sends the job to the DLQ; replaying it from Dead letters succeeds."),
                new ScenarioInfo("timeout", "Handler timeout",
                        "A slow inventory reservation exceeds its SLA and is stopped by the watchdog."),
                new ScenarioInfo("cancel", "Cancellation",
                        "Cancel a delayed shipment before the warehouse starts work."),
                new ScenarioInfo("concurrency", "Bounded concurrency",
                        "Release a batch of orders while worker slots bound parallel fulfillment."),
                new ScenarioInfo("bulk-checkout", "Flash-sale batch",
                        "Queue nine premium, standard, and economy orders together during a flash sale."),
                new ScenarioInfo("payment-outage", "Gateway outage recovery",
                        "Three payment captures hit the same temporary outage and recover through backoff."),
                new ScenarioInfo("warehouse-closure", "Warehouse closure",
                        "Cancel four scheduled shipments before the warehouse can reserve inventory."));
    }

    public ScenarioResult run(String name) {
        return switch (name) {
            case "success" -> success();
            case "idempotency" -> idempotency();
            case "priority" -> priority();
            case "delay" -> delay();
            case "retry" -> retry();
            case "dlq" -> dlq();
            case "timeout" -> timeout();
            case "cancel" -> cancel();
            case "concurrency" -> concurrency();
            case "bulk-checkout" -> bulkCheckout();
            case "payment-outage" -> paymentOutage();
            case "warehouse-closure" -> warehouseClosure();
            default -> throw new IllegalArgumentException("Unknown scenario: " + name);
        };
    }

    private ScenarioResult success() {
        JobSubmissionResponse job = submit("checkout", request("process-order",
                Map.of("orderId", orderId(), "customer", "Asha", "items", 2),
        "NORMAL", null, null, null));
        return result("success", List.of(job), "Poll the job until it reaches SUCCESS.", Map.of());
    }

    private ScenarioResult idempotency() {
        String key = "idempotent-" + UUID.randomUUID();
        String orderId = orderId();
        JobSubmissionRequest firstRequest = request("process-order",
                Map.of("orderId", orderId, "customer", "Ravi", "items", 1),
                "NORMAL", null, null, null);
        firstRequest.setIdempotencyKey(key);
        JobSubmissionRequest secondRequest = request("process-order",
                Map.of("orderId", orderId, "customer", "Ravi", "items", 1),
                "NORMAL", null, null, null);
        secondRequest.setIdempotencyKey(key);
        JobSubmissionResponse first = submit("demo", firstRequest);
        JobSubmissionResponse second = submit("demo", secondRequest);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sameJobId", first.getJobId().equals(second.getJobId()));
        details.put("idempotencyKey", key);
        return result("idempotency", List.of(first, second),
                "The second submission returns the original job instead of enqueuing a duplicate.", details);
    }

    private ScenarioResult priority() {
        Instant due = Instant.now().plusSeconds(2);
        Map<String, Object> payload = Map.of("millis", 1500);
        List<JobSubmissionResponse> jobs = List.of(
                submit("checkout", request("fulfill-order", orderPayload("ORD-ECONOMY"), "LOW", due, null, null)),
                submit("checkout", request("fulfill-order", orderPayload("ORD-STANDARD"), "NORMAL", due, null, null)),
                submit("checkout", request("fulfill-order", orderPayload("ORD-PREMIUM"), "HIGH", due, null, null)));
        return result("priority", jobs,
                "All three jobs become due together; the weighted scheduler services the lanes according to their configured shares.",
                Map.of());
    }

    private ScenarioResult delay() {
        Instant due = Instant.now().plusSeconds(10);
        JobSubmissionResponse job = submit("demo",
                request("fulfill-order", orderPayload(orderId()), "NORMAL", due, null, null));
        return result("delay", List.of(job), "The job stays QUEUED until nextRunAt is reached.",
                Map.of("nextRunAt", due));
    }

    private ScenarioResult retry() {
        JobSubmissionResponse job = submit("demo",
                request("charge-payment", Map.of("orderId", orderId(), "failures", 2),
                        "HIGH", null, 3, null));
        return result("retry", List.of(job),
                "Two failures are retried with exponential backoff; attempt three succeeds.", Map.of());
    }

    private ScenarioResult dlq() {
        JobSubmissionResponse job = submit("demo",
                request("dlq-demo", Map.of("orderId", orderId()), "HIGH", null, 1, null));
        return result("dlq", List.of(job),
                "The first attempt fails and the job moves to DLQ. Open Dead letters and replay it once to succeed.",
                Map.of("nextStep", "Open Dead letters"));
    }

    private ScenarioResult timeout() {
        JobSubmissionResponse job = submit("demo",
                request("reserve-inventory", Map.of("orderId", orderId(), "millis", 5000),
                        "HIGH", null, 1, 1));
        return result("timeout", List.of(job),
                "The one-second timeout interrupts the handler before it can complete.", Map.of());
    }

    private ScenarioResult cancel() {
        JobSubmissionResponse job = submit("demo",
                request("fulfill-order", orderPayload(orderId()), "NORMAL",
                        Instant.now().plusSeconds(60), null, null));
        submissionService.cancelJob(job.getJobId());
        JobResponse cancelled = submissionService.getJob(job.getJobId());
        return result("cancel", List.of(job),
                "The delayed job was cancelled while still QUEUED.",
                Map.of("cancelledStatus", cancelled.getStatus(), "cancelReason", cancelled.getResult()));
    }

    private ScenarioResult concurrency() {
        List<JobSubmissionResponse> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            jobs.add(submit("checkout", request("fulfill-order",
                    Map.of("orderId", orderId(), "millis", 3000), "NORMAL",
                    null, 1, null)));
        }
        return result("concurrency", jobs,
                "Six jobs are submitted, but at most " + properties.getExecutor().getMaxPoolSize()
                        + " run at once.", Map.of("maxPoolSize", properties.getExecutor().getMaxPoolSize()));
    }

    private ScenarioResult bulkCheckout() {
        List<JobSubmissionResponse> jobs = new ArrayList<>();
        String[][] lanes = {
                {"HIGH", "Priya"}, {"HIGH", "Marcus"}, {"HIGH", "Elena"},
                {"NORMAL", "Ravi"}, {"NORMAL", "Sofia"}, {"NORMAL", "Ben"},
                {"LOW", "Marta"}, {"LOW", "Kenji"}, {"LOW", "Uma"}
        };
        for (String[] lane : lanes) {
            Map<String, Object> payload = Map.of(
                    "orderId", orderId(),
                    "customer", lane[1],
                    "items", 2);
            jobs.add(submit("checkout", request("process-order", payload, lane[0], null, null, null)));
        }
        return result("bulk-checkout", jobs,
                "Nine orders across the three priority lanes were accepted for the flash sale.",
                Map.of("high", 3, "normal", 3, "low", 3));
    }

    private ScenarioResult paymentOutage() {
        List<JobSubmissionResponse> jobs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            jobs.add(submit("checkout",
                    request("charge-payment", Map.of("orderId", orderId(), "failures", 2),
                            "HIGH", null, 3, null)));
        }
        return result("payment-outage", jobs,
                "The same temporary gateway outage strikes three orders; each recovers with exponential backoff.",
                Map.of("orders", jobs.size(), "failedAttemptsBeforeRecovery", 2, "maxAttempts", 3));
    }

    private ScenarioResult warehouseClosure() {
        Instant due = Instant.now().plusSeconds(120);
        List<JobSubmissionResponse> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(submit("fulfillment",
                    request("fulfill-order", orderPayload(orderId()), "NORMAL", due, null, null)));
        }
        List<String> statuses = new ArrayList<>();
        for (JobSubmissionResponse job : jobs) {
            submissionService.cancelJob(job.getJobId());
            statuses.add(submissionService.getJob(job.getJobId()).getStatus());
        }
        return result("warehouse-closure", jobs,
                "Four scheduled shipments were cancelled before the warehouse could reserve inventory.",
                Map.of("cancelled", statuses.size(), "statuses", statuses));
    }

    private JobSubmissionResponse submit(String producer, JobSubmissionRequest request) {
        return submissionService.submit(producer, request);
    }

    private String orderId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Map<String, Object> orderPayload(String orderId) {
        return Map.of("orderId", orderId, "customer", "Demo customer", "items", 2, "millis", 1500);
    }

    private JobSubmissionRequest request(String type, Map<String, Object> payload, String priority,
                                         Instant nextRunAt, Integer maxAttempts, Integer timeoutSeconds) {
        JobSubmissionRequest request = new JobSubmissionRequest();
        request.setJobType(type);
        request.setIdempotencyKey(UUID.randomUUID().toString());
        request.setPriority(priority);
        request.setPayload(payload);
        request.setNextRunAt(nextRunAt);
        request.setMaxAttempts(maxAttempts);
        request.setTimeoutSeconds(timeoutSeconds);
        return request;
    }

    private ScenarioResult result(String name, List<JobSubmissionResponse> jobs, String note,
                                  Map<String, Object> details) {
        return new ScenarioResult(name, note, jobs, details);
    }

    public record ScenarioInfo(String name, String title, String description) {
    }

    public record ScenarioResult(String scenario, String note, List<JobSubmissionResponse> jobs,
                                 Map<String, Object> details) {
    }
}
