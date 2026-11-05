package io.github.learnerview.simplydone4j.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceTest {

    private static final String ORDER_ID = "ORD-1";
    private static final String SKU = "cloud-runner";

    @Mock OrderStore orders;
    @Mock InventoryService inventory;
    @Mock NotificationService notifications;

    OrderLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new OrderLifecycleService(orders, inventory, notifications);
    }

    @Test
    void shouldNotReleaseStockWhenCancelLosesToATerminalTransition() {
        OrderRecord completed = order(OrderStatus.COMPLETED, OrderStage.DELIVERED);
        when(orders.update(eq(ORDER_ID), any())).thenReturn(completed);

        OrderRecord result = lifecycle.cancel(ORDER_ID, "Cancelled by operator");

        assertSame(completed, result);
        verifyNoInteractions(inventory, notifications);
    }

    @Test
    void shouldApplyCancelSideEffectsOnlyAfterTheTransition() {
        OrderRecord accepted = order(OrderStatus.ACCEPTED, OrderStage.INVENTORY_RESERVED);
        when(orders.update(eq(ORDER_ID), any())).thenAnswer(invocation -> {
            UnaryOperator<OrderRecord> mutation = invocation.getArgument(1);
            return mutation.apply(accepted);
        });

        OrderRecord result = lifecycle.cancel(ORDER_ID, "Cancelled by operator");

        assertEquals(OrderStatus.CANCELLED, result.status());
        verify(inventory).releaseOnce(ORDER_ID, SKU, 2);
        verify(notifications).record("ORDER_CANCELLED",
                ORDER_ID + " cancelled: Cancelled by operator");
    }

    @Test
    void shouldNotReleaseStockForAFailureAfterShipment() {
        OrderRecord shipped = order(OrderStatus.PROCESSING, OrderStage.SHIPPED);
        when(orders.update(eq(ORDER_ID), any())).thenAnswer(invocation -> {
            UnaryOperator<OrderRecord> mutation = invocation.getArgument(1);
            return mutation.apply(shipped);
        });

        OrderRecord result = lifecycle.fail(ORDER_ID, "carrier rejected the delivery");

        assertEquals(OrderStatus.FAILED, result.status());
        verifyNoInteractions(inventory);
        verify(notifications).record("ORDER_FAILED",
                ORDER_ID + " needs attention: carrier rejected the delivery");
    }

    @Test
    void shouldReleaseStockForAFailureBeforeShipment() {
        OrderRecord packing = order(OrderStatus.PROCESSING, OrderStage.PACKING);
        when(orders.update(eq(ORDER_ID), any())).thenAnswer(invocation -> {
            UnaryOperator<OrderRecord> mutation = invocation.getArgument(1);
            return mutation.apply(packing);
        });

        lifecycle.fail(ORDER_ID, "payment failed");

        verify(inventory).releaseOnce(ORDER_ID, SKU, 2);
    }

    @Test
    void shouldNotifyCompletionOnlyWhenThisCallCompletedTheOrder() {
        OrderRecord completed = order(OrderStatus.COMPLETED, OrderStage.DELIVERED);
        when(orders.update(eq(ORDER_ID), any())).thenReturn(completed);

        OrderRecord result = lifecycle.complete(ORDER_ID, "already done", null);

        assertSame(completed, result);
        verifyNoInteractions(inventory, notifications);
    }

    private OrderRecord order(OrderStatus status, OrderStage stage) {
        Instant now = Instant.now();
        return new OrderRecord(ORDER_ID, "job-1", "Ada", SKU, "Cloud Runner", 2, 100, 200,
                "NORMAL", status, stage, now, now, null, null, List.of());
    }
}
