package io.github.learnerview.simplydone4j.demo;

public enum OrderStage {
    INVENTORY_RESERVED(0),
    PAYMENT_PENDING(1),
    PAYMENT_CAPTURED(2),
    PACKING(3),
    SHIPPED(4),
    DELIVERED(5),
    CANCELLED(99),
    FAILED(99);

    private final int rank;

    OrderStage(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(OrderStage other) {
        return rank >= other.rank;
    }
}
