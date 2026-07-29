package io.github.learnerview.simplydone4j.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simplydone4j")
public final class SimplyDoneProperties {

    private final Scheduler scheduler = new Scheduler();
    private final RateLimit rateLimit = new RateLimit();
    private final Retry retry = new Retry();
    private final Worker worker = new Worker();
    private final Queue queue = new Queue();
    private final Executor executor = new Executor();

    public Scheduler getScheduler() { return scheduler; }
    public RateLimit getRateLimit() { return rateLimit; }
    public Retry getRetry() { return retry; }
    public Worker getWorker() { return worker; }
    public Queue getQueue() { return queue; }
    public Executor getExecutor() { return executor; }

    public static final class Scheduler {
        private long pollingIntervalMs = 1000L;
        private String queuePrefix = "simplydone4j:queue";
        private final Weights weights = new Weights();

        public long getPollingIntervalMs() { return pollingIntervalMs; }
        public void setPollingIntervalMs(long pollingIntervalMs) { this.pollingIntervalMs = pollingIntervalMs; }
        public String getQueuePrefix() { return queuePrefix; }
        public void setQueuePrefix(String queuePrefix) { this.queuePrefix = queuePrefix; }
        public Weights getWeights() { return weights; }

        public static final class Weights {
            private int high = 70;
            private int normal = 20;
            private int low = 10;
            public int getHigh() { return high; }
            public void setHigh(int high) { this.high = high; }
            public int getNormal() { return normal; }
            public void setNormal(int normal) { this.normal = normal; }
            public int getLow() { return low; }
            public void setLow(int low) { this.low = low; }
        }
    }

    public static final class RateLimit {
        private int requestsPerMinute = 60;
        private int windowSeconds = 60;
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }

    public static final class Retry {
        private int maxAttempts = 3;
        private int initialDelaySeconds = 5;
        private double backoffMultiplier = 2.0;
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getInitialDelaySeconds() { return initialDelaySeconds; }
        public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    }

    public static final class Worker {
        private int leaseTimeoutSeconds = 30;
        private long retryPromoterIntervalMs = 1000L;
        private long leaseReaperIntervalMs = 5000L;
        public int getLeaseTimeoutSeconds() { return leaseTimeoutSeconds; }
        public void setLeaseTimeoutSeconds(int leaseTimeoutSeconds) { this.leaseTimeoutSeconds = leaseTimeoutSeconds; }
        public long getRetryPromoterIntervalMs() { return retryPromoterIntervalMs; }
        public void setRetryPromoterIntervalMs(long retryPromoterIntervalMs) { this.retryPromoterIntervalMs = retryPromoterIntervalMs; }
        public long getLeaseReaperIntervalMs() { return leaseReaperIntervalMs; }
        public void setLeaseReaperIntervalMs(long leaseReaperIntervalMs) { this.leaseReaperIntervalMs = leaseReaperIntervalMs; }
    }

    public static final class Queue {
        private long maxDepth = 10000L;
        public long getMaxDepth() { return maxDepth; }
        public void setMaxDepth(long maxDepth) { this.maxDepth = maxDepth; }
    }

    public static final class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        private int defaultTimeoutSeconds = 30;
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public int getKeepAliveSeconds() { return keepAliveSeconds; }
        public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }
        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }
    }
}
