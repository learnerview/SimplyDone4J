package io.github.learnerview.simplydone4j.demo;

import lombok.Builder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Builder(toBuilder = true)
public record OrderRecord(
        String orderId,
        String jobId,
        String customer,
        String sku,
        String productName,
        int quantity,
        int unitPrice,
        int total,
        String priority,
        OrderStatus status,
        OrderStage stage,
        Instant createdAt,
        Instant updatedAt,
        String result,
        String trackingNumber,
        List<OrderEvent> timeline) {

    private static final int MAX_TIMELINE_EVENTS = 50;

    public OrderRecord {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public boolean terminal() {
        return status == OrderStatus.COMPLETED
                || status == OrderStatus.CANCELLED
                || status == OrderStatus.FAILED;
    }

    public OrderRecord withJob(String newJobId, String newPriority) {
        return toBuilder()
                .jobId(newJobId)
                .priority(newPriority)
                .build();
    }

    public OrderRecord withProgress(OrderStatus newStatus, OrderStage newStage, String message,
                                    String newResult, String newTrackingNumber) {
        Instant now = Instant.now();
        List<OrderEvent> events = new ArrayList<>(timeline);
        events.add(new OrderEvent(now, newStage, message));
        if (events.size() > MAX_TIMELINE_EVENTS) {
            events = new ArrayList<>(events.subList(events.size() - MAX_TIMELINE_EVENTS, events.size()));
        }
        return toBuilder()
                .status(newStatus)
                .stage(newStage)
                .updatedAt(now)
                .result(newResult)
                .trackingNumber(newTrackingNumber)
                .timeline(events)
                .build();
    }
}
