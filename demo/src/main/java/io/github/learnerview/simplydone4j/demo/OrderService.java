package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobResponse;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.exception.JobNotFoundException;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore store;
    private final FulfillmentWorkflowService workflow;
    private final JobSubmissionService jobs;
    private final NotificationService notifications;
    private final InventoryService inventory;
    private final DemoCatalogService catalog;
    private final OrderLifecycleService lifecycle;

    public OrderService(OrderStore store, FulfillmentWorkflowService workflow,
                        JobSubmissionService jobs, NotificationService notifications,
                        InventoryService inventory, DemoCatalogService catalog,
                        OrderLifecycleService lifecycle) {
        this.store = store;
        this.workflow = workflow;
        this.jobs = jobs;
        this.notifications = notifications;
        this.inventory = inventory;
        this.catalog = catalog;
        this.lifecycle = lifecycle;
    }

    public OrderRecord place(PlaceOrderRequest request) {
        DemoCatalogService.Product product = catalog.require(request.sku());
        int quantity = request.quantity();
        if (!inventory.reserve(product.sku(), quantity)) {
            throw new InsufficientStockException(product.name(), quantity);
        }

        String orderId = workflow.newOrderId();
        Instant now = Instant.now();
        String priority = workflow.normalizePriority(request.priority());
        OrderRecord pending = OrderRecord.builder()
                .orderId(orderId)
                .customer(request.customer())
                .sku(product.sku())
                .productName(product.name())
                .quantity(quantity)
                .unitPrice(product.price())
                .total(product.price() * quantity)
                .priority(priority)
                .status(OrderStatus.ACCEPTED)
                .stage(OrderStage.INVENTORY_RESERVED)
                .createdAt(now)
                .updatedAt(now)
                .timeline(List.of(new OrderEvent(now, OrderStage.INVENTORY_RESERVED,
                        "Order accepted; " + quantity + " x " + product.name() + " reserved")))
                .build();

        String jobId = null;
        boolean saved = false;
        OrderRecord accepted;
        try {
            store.save(pending);
            saved = true;
            JobSubmissionResponse submission = workflow.enqueueOrder(pending,
                    request.simulatePaymentRetry() ? 2 : 0);
            String acceptedJobId = submission.getJobId();
            jobId = acceptedJobId;
            accepted = store.update(orderId,
                    order -> order.withJob(acceptedJobId, submission.getPriority()));
        } catch (RuntimeException failure) {
            if (saved && jobId == null) {
                try {
                    jobId = workflow.findOrderJobId(orderId).orElse(null);
                } catch (RuntimeException lookupFailure) {
                    failure.addSuppressed(lookupFailure);
                    log.error("Cannot determine whether order {} has a job; leaving the order and "
                            + "its inventory reservation in place", orderId, lookupFailure);
                    throw failure;
                }
            }
            if (jobId != null && !safeToRollback(jobId)) {
                log.error("Leaving order {} and its inventory reservation in place because job {} "
                        + "could not be cancelled safely", orderId, jobId);
                throw failure;
            }
            try {
                inventory.releaseOnce(orderId, product.sku(), quantity);
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
                log.warn("Could not release inventory for incomplete order {}", orderId, releaseFailure);
            }
            try {
                store.delete(orderId);
            } catch (RuntimeException deleteFailure) {
                failure.addSuppressed(deleteFailure);
                log.warn("Could not delete incomplete order {}", orderId, deleteFailure);
            }
            throw failure;
        }

        notifications.record("ORDER_ACCEPTED", orderId + " accepted for " + request.customer());
        return accepted == null ? pending : accepted;
    }

    private boolean safeToRollback(String jobId) {
        JobResponse job;
        try {
            job = jobs.getJob(jobId);
        } catch (JobNotFoundException e) {
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not verify job {} before rollback; keeping its order", jobId, e);
            return false;
        }

        if ("CANCELLED".equals(job.getStatus())) {
            return true;
        }
        if (!"QUEUED".equals(job.getStatus())) {
            log.warn("Job {} is already {}; refusing to discard its order", jobId, job.getStatus());
            return false;
        }

        try {
            jobs.cancelJob(jobId);
            return true;
        } catch (RuntimeException cancelFailure) {
            try {
                if ("CANCELLED".equals(jobs.getJob(jobId).getStatus())) {
                    return true;
                }
            } catch (JobNotFoundException missing) {
                return true;
            } catch (RuntimeException rereadFailure) {
                cancelFailure.addSuppressed(rereadFailure);
            }
            log.warn("Could not verify cancellation of job {}; keeping its order", jobId,
                    cancelFailure);
            return false;
        }
    }

    private JobResponse findJob(String jobId) {
        try {
            return jobs.getJob(jobId);
        } catch (JobNotFoundException e) {
            return null;
        }
    }

    private boolean jobCannotBeCancelled(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "DLQ".equals(status);
    }

    public OrderRecord find(String orderId) {
        OrderRecord order = store.find(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Unknown order: " + orderId);
        }
        return order;
    }

    public List<OrderRecord> recent() {
        return store.recent();
    }

    public OrderRecord cancel(String orderId) {
        OrderRecord current = find(orderId);
        if (current.terminal()) {
            throw new IllegalArgumentException("Order " + orderId + " is already " + current.status());
        }
        if (current.stage().isAtLeast(OrderStage.SHIPPED)) {
            throw new IllegalArgumentException("Order " + orderId + " has already shipped");
        }

        if (current.jobId() != null) {
            JobResponse job = findJob(current.jobId());
            if (job == null) {
                log.info("Order {} references missing job {}; cancelling the order record",
                        orderId, current.jobId());
            } else if (jobCannotBeCancelled(job.getStatus())) {
                throw new IllegalArgumentException("Order " + orderId
                        + " job is already " + job.getStatus() + "; refresh the order");
            } else if ("QUEUED".equals(job.getStatus())) {
                try {
                    jobs.cancelJob(current.jobId());
                } catch (IllegalArgumentException race) {
                    JobResponse latest = findJob(current.jobId());
                    if (latest != null && jobCannotBeCancelled(latest.getStatus())) {
                        throw new IllegalArgumentException("Order " + orderId
                                + " job is already " + latest.getStatus() + "; refresh the order");
                    }
                    log.info("Order {} job left QUEUED before cancellation; marking the order "
                            + "cancelled so the handler stops at its next checkpoint", orderId);
                }
            } else {
                log.info("Order {} job is {}; marking the order cancelled so the handler stops "
                        + "at its next checkpoint", orderId, job.getStatus());
            }
        }

        OrderRecord cancelled = lifecycle.cancel(orderId, "Cancelled by operator");
        return cancelled == null ? find(orderId) : cancelled;
    }

    public OrderStats stats() {
        long tracked = 0;
        long active = 0;
        long completed = 0;
        long cancelled = 0;
        long failed = 0;
        long revenue = 0;
        long unitsShipped = 0;

        for (OrderRecord order : store.all()) {
            tracked++;
            switch (order.status()) {
                case COMPLETED -> {
                    completed++;
                    revenue += order.total();
                    unitsShipped += order.quantity();
                }
                case CANCELLED -> cancelled++;
                case FAILED -> failed++;
                default -> active++;
            }
        }
        return new OrderStats(tracked, active, completed, cancelled, failed, revenue, unitsShipped);
    }

    public record OrderStats(long tracked, long active, long completed, long cancelled,
                             long failed, long revenue, long unitsShipped) {
    }
}
