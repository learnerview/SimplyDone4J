package io.github.learnerview.simplydone4j.dto;

public final class QueueStatsResponse {
    private final long highQueueSize;
    private final long normalQueueSize;
    private final long lowQueueSize;
    private final long totalQueued;
    private final long totalRunning;
    private final long totalSuccess;
    private final long totalFailed;
    private final long totalDlq;
    private final long totalProcessed;
    private final double successRate;
    private final double inFlightRetryRate;

    private QueueStatsResponse(Builder builder) {
        this.highQueueSize = builder.highQueueSize;
        this.normalQueueSize = builder.normalQueueSize;
        this.lowQueueSize = builder.lowQueueSize;
        this.totalQueued = builder.totalQueued;
        this.totalRunning = builder.totalRunning;
        this.totalSuccess = builder.totalSuccess;
        this.totalFailed = builder.totalFailed;
        this.totalDlq = builder.totalDlq;
        this.totalProcessed = builder.totalProcessed;
        this.successRate = builder.successRate;
        this.inFlightRetryRate = builder.inFlightRetryRate;
    }

    public long getHighQueueSize() { return highQueueSize; }
    public long getNormalQueueSize() { return normalQueueSize; }
    public long getLowQueueSize() { return lowQueueSize; }
    public long getTotalQueued() { return totalQueued; }
    public long getTotalRunning() { return totalRunning; }
    public long getTotalSuccess() { return totalSuccess; }
    public long getTotalFailed() { return totalFailed; }
    public long getTotalDlq() { return totalDlq; }
    public long getTotalProcessed() { return totalProcessed; }
    public double getSuccessRate() { return successRate; }
    public double getInFlightRetryRate() { return inFlightRetryRate; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private long highQueueSize;
        private long normalQueueSize;
        private long lowQueueSize;
        private long totalQueued;
        private long totalRunning;
        private long totalSuccess;
        private long totalFailed;
        private long totalDlq;
        private long totalProcessed;
        private double successRate;
        private double inFlightRetryRate;
        private Builder() {}
        public Builder highQueueSize(long v) { this.highQueueSize = v; return this; }
        public Builder normalQueueSize(long v) { this.normalQueueSize = v; return this; }
        public Builder lowQueueSize(long v) { this.lowQueueSize = v; return this; }
        public Builder totalQueued(long v) { this.totalQueued = v; return this; }
        public Builder totalRunning(long v) { this.totalRunning = v; return this; }
        public Builder totalSuccess(long v) { this.totalSuccess = v; return this; }
        public Builder totalFailed(long v) { this.totalFailed = v; return this; }
        public Builder totalDlq(long v) { this.totalDlq = v; return this; }
        public Builder totalProcessed(long v) { this.totalProcessed = v; return this; }
        public Builder successRate(double v) { this.successRate = v; return this; }
        public Builder inFlightRetryRate(double v) { this.inFlightRetryRate = v; return this; }
        public QueueStatsResponse build() { return new QueueStatsResponse(this); }
    }
}
