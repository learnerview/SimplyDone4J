package io.github.learnerview.simplydone4j.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.JobContext;
import org.springframework.stereotype.Service;

@Service
public class OrderFulfillmentService {

    private static final long STAGE_DELAY_MILLIS = 350L;

    private final OrderStore orders;
    private final OrderLifecycleService lifecycle;
    private final NotificationService notifications;
    private final ObjectMapper objectMapper;

    public OrderFulfillmentService(OrderStore orders, OrderLifecycleService lifecycle,
                                   NotificationService notifications, ObjectMapper objectMapper) {
        this.orders = orders;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
    }

    public String fulfill(JobContext context) throws InterruptedException {
        JsonNode payload = payload(context);
        String orderId = text(payload, "orderId");
        if (orderId == null) {
            throw new IllegalStateException("Fulfillment job is missing its orderId");
        }
        OrderRecord order = orders.find(orderId);
        if (order == null) {
            throw new IllegalStateException("Order not found: " + orderId);
        }
        if (order.status() == OrderStatus.CANCELLED) {
            return "Order " + orderId + " was cancelled before fulfillment";
        }
        if (order.status() == OrderStatus.FAILED) {
            return order.result() == null ? "Order " + orderId + " has already failed" : order.result();
        }
        if (order.status() == OrderStatus.COMPLETED) {
            return order.result() == null ? "Order " + orderId + " is already delivered" : order.result();
        }

        int attempt = context.getAttemptCount();
        int simulatedFailures = intValue(payload, "failures", 0);
        transition(orderId, OrderStatus.PROCESSING, order.stage(),
                "Fulfillment attempt " + (attempt + 1) + " started", null, null);

        order = orders.find(orderId);
        if (!order.stage().isAtLeast(OrderStage.PAYMENT_CAPTURED)) {
            transition(orderId, OrderStatus.PROCESSING, OrderStage.PAYMENT_PENDING,
                    "Authorizing payment of $" + order.total(), null, null);
            Thread.sleep(STAGE_DELAY_MILLIS);
            if (cancelled(orderId)) {
                return cancelledMessage(orderId);
            }
            if (attempt < simulatedFailures) {
                throw new IllegalStateException("Payment gateway temporarily unavailable");
            }
            transition(orderId, OrderStatus.PROCESSING, OrderStage.PAYMENT_CAPTURED,
                    "Payment captured for $" + order.total(), null, null);
            notifications.record("PAYMENT_CAPTURED", orderId + " payment captured ($" + order.total() + ")");
        }

        order = orders.find(orderId);
        if (cancelled(order)) {
            return cancelledMessage(orderId);
        }
        if (!order.stage().isAtLeast(OrderStage.PACKING)) {
            Thread.sleep(STAGE_DELAY_MILLIS);
            if (cancelled(orderId)) {
                return cancelledMessage(orderId);
            }
            transition(orderId, OrderStatus.PROCESSING, OrderStage.PACKING,
                    "Items picked and packed", null, null);
        }

        order = orders.find(orderId);
        if (cancelled(order)) {
            return cancelledMessage(orderId);
        }
        String tracking = order.trackingNumber();
        if (!order.stage().isAtLeast(OrderStage.SHIPPED)) {
            Thread.sleep(STAGE_DELAY_MILLIS);
            if (cancelled(orderId)) {
                return cancelledMessage(orderId);
            }
            tracking = trackingNumber(orderId);
            transition(orderId, OrderStatus.PROCESSING, OrderStage.SHIPPED,
                    "Handed to the carrier", null, tracking);
            notifications.record("ORDER_SHIPPED", orderId + " shipped with tracking " + tracking);
        }

        order = orders.find(orderId);
        if (cancelled(order)) {
            return cancelledMessage(orderId);
        }
        if (!order.stage().isAtLeast(OrderStage.DELIVERED)) {
            Thread.sleep(STAGE_DELAY_MILLIS);
            if (cancelled(orderId)) {
                return cancelledMessage(orderId);
            }
        }
        order = orders.find(orderId);
        if (cancelled(order)) {
            return cancelledMessage(orderId);
        }
        String result = "Delivered " + order.quantity() + " x " + order.productName()
                + " to " + order.customer() + " ($" + order.total() + ", tracking " + tracking + ")";
        lifecycle.complete(orderId, result, tracking);
        return result;
    }

    private boolean cancelled(OrderRecord order) {
        return order != null && order.status() == OrderStatus.CANCELLED;
    }

    private boolean cancelled(String orderId) {
        return cancelled(orders.find(orderId));
    }

    private String cancelledMessage(String orderId) {
        return "Order " + orderId + " was cancelled before delivery";
    }

    private void transition(String orderId, OrderStatus status, OrderStage stage, String message,
                            String result, String trackingNumber) {
        orders.update(orderId, order -> order.terminal()
                ? order
                : order.withProgress(status, stage, message, result, trackingNumber));
    }

    private String trackingNumber(String orderId) {
        String suffix = orderId.substring(Math.max(0, orderId.length() - 8));
        return "PF-" + suffix;
    }

    private JsonNode payload(JobContext context) {
        if (context.getPayload() == null || context.getPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(context.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("Fulfillment job has invalid JSON payload", e);
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private int intValue(JsonNode node, String field, int fallback) {
        return node != null && node.has(field) ? node.get(field).asInt(fallback) : fallback;
    }
}
