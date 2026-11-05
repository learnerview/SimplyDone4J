package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.repository.JobRepository;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class FulfillmentWorkflowService {

    private static final String PRODUCER = "checkout";

    private final JobSubmissionService jobs;
    private final JobRepository jobRepository;

    public FulfillmentWorkflowService(JobSubmissionService jobs, JobRepository jobRepository) {
        this.jobs = jobs;
        this.jobRepository = jobRepository;
    }

    public JobSubmissionResponse enqueueOrder(OrderRecord order, int simulatedFailures) {
        JobSubmissionRequest job = new JobSubmissionRequest();
        job.setJobType("fulfill-product-order");
        job.setIdempotencyKey(idempotencyKey(order.orderId()));
        job.setPriority(order.priority());
        job.setPayload(Map.of(
                "orderId", order.orderId(),
                "failures", simulatedFailures));
        job.setMaxAttempts(3);
        job.setTimeoutSeconds(20);
        return jobs.submit(PRODUCER, job);
    }

    /**
     * Resolves the job a previous enqueue attempt may have persisted even if the submission
     * call failed before returning its id.
     */
    public Optional<String> findOrderJobId(String orderId) {
        return jobRepository.findByProducerAndIdempotencyKey(PRODUCER, idempotencyKey(orderId))
                .map(job -> job.getId());
    }

    private String idempotencyKey(String orderId) {
        return "order-" + orderId;
    }

    public String newOrderId() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public String normalizePriority(String priority) {
        return priority == null || priority.isBlank() ? "NORMAL" : priority.toUpperCase();
    }
}
