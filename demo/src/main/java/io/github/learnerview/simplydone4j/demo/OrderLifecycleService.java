package io.github.learnerview.simplydone4j.demo;

import org.springframework.stereotype.Service;

@Service
public class OrderLifecycleService {

    private final OrderStore orders;
    private final InventoryService inventory;
    private final NotificationService notifications;

    public OrderLifecycleService(OrderStore orders, InventoryService inventory,
                                 NotificationService notifications) {
        this.orders = orders;
        this.inventory = inventory;
        this.notifications = notifications;
    }

    public OrderRecord cancel(String orderId, String reason) {
        boolean[] transitioned = {false};
        OrderRecord cancelled = orders.update(orderId, order -> {
            if (order.terminal()) {
                return order;
            }
            transitioned[0] = true;
            return order.withProgress(OrderStatus.CANCELLED, OrderStage.CANCELLED,
                    reason, reason, null);
        });
        if (transitioned[0]) {
            inventory.releaseOnce(orderId, cancelled.sku(), cancelled.quantity());
            notifications.record("ORDER_CANCELLED", orderId + " cancelled: " + reason);
        }
        return cancelled;
    }

    public OrderRecord fail(String orderId, String reason) {
        boolean[] transitioned = {false};
        boolean[] shipped = {false};
        OrderRecord failed = orders.update(orderId, order -> {
            if (order.terminal()) {
                return order;
            }
            transitioned[0] = true;
            shipped[0] = order.stage().isAtLeast(OrderStage.SHIPPED);
            return order.withProgress(OrderStatus.FAILED, OrderStage.FAILED,
                    reason, reason, null);
        });
        if (transitioned[0]) {
            if (!shipped[0]) {
                inventory.releaseOnce(orderId, failed.sku(), failed.quantity());
            }
            notifications.record("ORDER_FAILED", orderId + " needs attention: " + reason);
        }
        return failed;
    }

    public OrderRecord retry(String orderId, int nextAttempt, int maxAttempts) {
        OrderRecord current = orders.find(orderId);
        if (current == null || current.terminal()) {
            return current;
        }
        String message = "Attempt " + nextAttempt + " of " + maxAttempts + " scheduled after a failure";
        return orders.update(orderId, order -> order.terminal()
                ? order
                : order.withProgress(OrderStatus.RETRYING, order.stage(), message, null, null));
    }

    public OrderRecord complete(String orderId, String result, String trackingNumber) {
        boolean[] transitioned = {false};
        OrderRecord completed = orders.update(orderId, order -> {
            if (order.terminal()) {
                return order;
            }
            transitioned[0] = true;
            return order.withProgress(OrderStatus.COMPLETED, OrderStage.DELIVERED,
                    "Order delivered to " + order.customer(), result, trackingNumber);
        });
        if (transitioned[0]) {
            notifications.record("ORDER_COMPLETED",
                    orderId + " delivered to " + completed.customer());
        }
        return completed;
    }
}
