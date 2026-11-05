package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.event.JobEvent;
import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class OrderLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleListener.class);

    private final OrderStore orders;
    private final OrderLifecycleService lifecycle;
    private final JobSubmissionService jobs;

    public OrderLifecycleListener(OrderStore orders, OrderLifecycleService lifecycle,
                                  JobSubmissionService jobs) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.jobs = jobs;
    }

    @EventListener
    public void onJobEvent(JobEventPublisher.JobPublishedEvent published) {
        OrderRecord order = findOrder(published.data().getJobId());
        if (order == null) {
            return;
        }
        JobEvent event = published.event();
        switch (event) {
            case JOB_RETRY -> lifecycle.retry(order.orderId(),
                    valueOr(published.data().getAttempt(), 1),
                    valueOr(published.data().getMaxAttempts(), 1));
            case JOB_FAILED -> lifecycle.fail(order.orderId(), failureReason(published));
            case JOB_CANCELLED -> lifecycle.cancel(order.orderId(), "Cancelled by user");
            case JOB_COMPLETED -> lifecycle.complete(order.orderId(),
                    published.data().getResult(), order.trackingNumber());
            default -> {
            }
        }
    }

    /**
     * The order/job association is written after submit returns. A fast failure can publish
     * its terminal event before that write lands, so fall back to the order id carried in
     * the job payload and repair the association while handling the event.
     */
    private OrderRecord findOrder(String jobId) {
        OrderRecord order = orders.findByJobId(jobId);
        if (order != null) {
            return order;
        }
        String orderId = payloadOrderId(jobId);
        if (orderId == null) {
            return null;
        }
        order = orders.find(orderId);
        if (order == null || order.jobId() != null) {
            return order;
        }
        OrderRecord bound = orders.update(orderId,
                current -> current.jobId() == null
                        ? current.withJob(jobId, current.priority())
                        : current);
        return bound == null ? order : bound;
    }

    private String payloadOrderId(String jobId) {
        try {
            JobResponse job = jobs.getJob(jobId);
            Object orderId = job.getPayload() == null ? null : job.getPayload().get("orderId");
            return orderId instanceof String value ? value : null;
        } catch (RuntimeException e) {
            log.warn("Could not resolve order for job {}", jobId, e);
            return null;
        }
    }

    private String failureReason(JobEventPublisher.JobPublishedEvent published) {
        String result = published.data().getResult();
        return result == null || result.isBlank() ? "Fulfillment job failed" : result;
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
