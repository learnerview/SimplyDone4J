package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DemoWorkloadService {

    private static final int MAX_JOBS = 200;
    private static final int MAX_ORDERS = 50;
    private static final Set<String> ORDER_JOBS = Set.of(
            "process-order", "fulfill-order", "reserve-inventory",
            "charge-payment", "fulfill-product-order");
    private static final String[] CUSTOMERS = {
            "Asha Patel", "Ravi Kumar", "Sofia Rossi", "Marcus Chen",
            "Elena Petrova", "Ben Carter", "Marta Silva", "Kenji Watanabe"
    };

    private final JobSubmissionService jobs;
    private final OrderService orders;
    private final DemoCatalogService catalog;

    public DemoWorkloadService(JobSubmissionService jobs, OrderService orders,
                               DemoCatalogService catalog) {
        this.jobs = jobs;
        this.orders = orders;
        this.catalog = catalog;
    }

    public JobBatchResult submitJobs(JobBatchRequest request) {
        String jobType = request.jobType() == null ? "" : request.jobType().trim();
        if (jobType.isEmpty()) {
            throw new IllegalArgumentException("jobType is required");
        }
        int count = Math.min(Math.max(request.count(), 1), MAX_JOBS);
        String priority = normalizePriority(request.priority());
        Map<String, Object> basePayload = request.payload() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.payload());
        long delayMs = Math.max(0, request.delayMs());
        long staggerMs = Math.max(0, request.staggerMs());
        Instant now = Instant.now();

        List<JobSubmissionResponse> accepted = new ArrayList<>();
        List<BatchFailure> failures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            try {
                JobSubmissionRequest job = new JobSubmissionRequest();
                job.setJobType(jobType);
                job.setIdempotencyKey("workload-" + UUID.randomUUID());
                job.setPriority(priority);
                job.setPayload(payload(jobType, basePayload, index));
                if (request.maxAttempts() != null) {
                    job.setMaxAttempts(request.maxAttempts());
                }
                if (request.timeoutSeconds() != null) {
                    job.setTimeoutSeconds(request.timeoutSeconds());
                }
                long offset = delayMs + (staggerMs * index);
                if (offset > 0) {
                    job.setNextRunAt(now.plusMillis(offset));
                }
                accepted.add(jobs.submit("workload", job));
            } catch (RuntimeException e) {
                failures.add(new BatchFailure(index,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new JobBatchResult(count, accepted.size(), failures.size(), accepted,
                failures, Instant.now());
    }

    public OrderBatchResult submitOrders(OrderBatchRequest request) {
        int count = Math.min(Math.max(request.count(), 1), MAX_ORDERS);
        int quantity = Math.min(Math.max(request.quantity(), 1), 5);
        String priority = normalizePriority(request.priority());
        List<DemoCatalogService.Product> products = catalog.products();
        if (products.isEmpty()) {
            throw new IllegalStateException("No products are configured");
        }
        String requestedSku = request.sku() == null ? "" : request.sku().trim();
        if (!requestedSku.isEmpty() && !catalog.exists(requestedSku)) {
            throw new IllegalArgumentException("Unknown product SKU: " + requestedSku);
        }

        List<OrderRecord> accepted = new ArrayList<>();
        List<BatchFailure> failures = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            DemoCatalogService.Product product = requestedSku.isEmpty()
                    ? products.get(index % products.size())
                    : catalog.require(requestedSku);
            try {
                accepted.add(orders.place(new PlaceOrderRequest(
                        CUSTOMERS[index % CUSTOMERS.length] + " #" + (index + 1),
                        product.sku(), quantity, priority, request.simulatePaymentRetry())));
            } catch (RuntimeException e) {
                failures.add(new BatchFailure(index,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new OrderBatchResult(count, accepted.size(), failures.size(), accepted,
                failures, Instant.now());
    }

    private Map<String, Object> payload(String jobType, Map<String, Object> base, int index) {
        Map<String, Object> payload = new LinkedHashMap<>(base);
        if (ORDER_JOBS.contains(jobType)) {
            payload.put("orderId", "LOAD-" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase(Locale.ROOT));
            payload.putIfAbsent("customer", CUSTOMERS[index % CUSTOMERS.length]);
            payload.putIfAbsent("items", 1);
        }
        return payload;
    }

    private String normalizePriority(String priority) {
        String value = priority == null || priority.isBlank()
                ? "NORMAL" : priority.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("HIGH", "NORMAL", "LOW").contains(value)) {
            throw new IllegalArgumentException("priority must be HIGH, NORMAL, or LOW");
        }
        return value;
    }

    public record JobBatchRequest(
            @NotBlank String jobType,
            @Min(1) @Max(MAX_JOBS) int count,
            String priority,
            Map<String, Object> payload,
            @Min(1) Integer maxAttempts,
            @Min(1) Integer timeoutSeconds,
            @Min(0) @Max(600_000) long delayMs,
            @Min(0) @Max(60_000) long staggerMs) {
    }

    public record OrderBatchRequest(
            @Min(1) @Max(MAX_ORDERS) int count,
            String sku,
            @Min(1) @Max(5) int quantity,
            String priority,
            boolean simulatePaymentRetry) {
    }

    public record BatchFailure(int index, String message) {
    }

    public record JobBatchResult(int requested, int accepted, int failed,
                                 List<JobSubmissionResponse> jobs,
                                 List<BatchFailure> failures,
                                 Instant generatedAt) {
    }

    public record OrderBatchResult(int requested, int accepted, int failed,
                                   List<OrderRecord> orders,
                                   List<BatchFailure> failures,
                                   Instant generatedAt) {
    }
}
