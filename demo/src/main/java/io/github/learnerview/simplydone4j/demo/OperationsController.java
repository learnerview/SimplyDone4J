package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.service.DeadLetterService;
import io.github.learnerview.simplydone4j.service.EngineStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final DemoMetricsService metrics;
    private final OrderService orders;
    private final DemoCatalogService catalog;
    private final NotificationService notifications;
    private final EngineStatusService engineStatus;
    private final DeadLetterService deadLetterService;

    public OperationsController(DemoMetricsService metrics, OrderService orders,
                                DemoCatalogService catalog, NotificationService notifications,
                                EngineStatusService engineStatus,
                                DeadLetterService deadLetterService) {
        this.metrics = metrics;
        this.orders = orders;
        this.catalog = catalog;
        this.notifications = notifications;
        this.engineStatus = engineStatus;
        this.deadLetterService = deadLetterService;
    }

    @GetMapping("/summary")
    public DemoMetricsService.OperationsSummary summary() {
        return metrics.summary(orders.stats(), engineStatus.status(), deadLetterService.count());
    }

    @GetMapping("/catalog")
    public List<DemoCatalogService.Product> catalog() {
        return catalog.products();
    }

    @GetMapping("/notifications")
    public List<NotificationService.Notification> notifications() {
        return notifications.recent();
    }

    @GetMapping("/architecture")
    public Map<String, Object> architecture() {
        return Map.of(
                "workflow", List.of("Checkout API", "Redis queue", "Lease-based worker", "Retry or DLQ", "Customer notification"),
                "whyItMatters", "The application code submits work; SimplyDone4J owns the distributed scheduling guarantees.",
                "guarantees", List.of("Idempotent submission", "Weighted priority", "Delayed execution", "Exponential backoff", "Lease fencing", "Dead-letter recovery"));
    }
}
