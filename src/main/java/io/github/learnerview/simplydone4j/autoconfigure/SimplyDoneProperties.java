package io.github.learnerview.simplydone4j.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "simplydone4j")
public final class SimplyDoneProperties {

    private final Scheduler scheduler = new Scheduler();
    private final Retry retry = new Retry();
    private final Worker worker = new Worker();
    private final Queue queue = new Queue();
    private final Submission submission = new Submission();
    private final Executor executor = new Executor();
    private final Retention retention = new Retention();
    private final Redis redis = new Redis();
    private int ttlDays = 0;
    private int idempotencyTtlHours = 1;
    private int ttlHours = 1;
    private String keyPrefix = "simplydone4j";

    public Scheduler getScheduler() { return scheduler; }
    public Retry getRetry() { return retry; }
    public Worker getWorker() { return worker; }
    public Queue getQueue() { return queue; }
    public Submission getSubmission() { return submission; }
    public Executor getExecutor() { return executor; }
    public Retention getRetention() { return retention; }
    public Redis getRedis() { return redis; }
    public int getTtlDays() { return ttlDays; }
    public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
    public int getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(int idempotencyTtlHours) { this.idempotencyTtlHours = idempotencyTtlHours; }
    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public static final class Scheduler {
        private boolean enabled = true;
        private long pollingIntervalMs = 1000L;
        private long busyPollingIntervalMs = 25L;
        private int maxClaimBatch = 256;
        private String queuePrefix;
        private final Weights weights = new Weights();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getPollingIntervalMs() { return pollingIntervalMs; }
        public void setPollingIntervalMs(long pollingIntervalMs) { this.pollingIntervalMs = pollingIntervalMs; }
        public long getBusyPollingIntervalMs() { return busyPollingIntervalMs; }
        public void setBusyPollingIntervalMs(long busyPollingIntervalMs) { this.busyPollingIntervalMs = busyPollingIntervalMs; }
        public int getMaxClaimBatch() { return maxClaimBatch; }
        public void setMaxClaimBatch(int maxClaimBatch) { this.maxClaimBatch = maxClaimBatch; }
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

    public static final class Retry {
        private int maxAttempts = 3;
        private int initialDelaySeconds = 5;
        private double backoffMultiplier = 2.0;
        private double jitterFactor = 0.1;
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public int getInitialDelaySeconds() { return initialDelaySeconds; }
        public void setInitialDelaySeconds(int initialDelaySeconds) { this.initialDelaySeconds = initialDelaySeconds; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public double getJitterFactor() { return jitterFactor; }
        public void setJitterFactor(double jitterFactor) { this.jitterFactor = jitterFactor; }
    }

    public static final class Worker {
        private int leaseTimeoutSeconds = 30;
        private int maintenancePoolSize = 3;
        private long retryPromoterIntervalMs = 1000L;
        private int retryPromoterScanLimit = 100;
        private long leaseReaperIntervalMs = 5000L;
        private int leaseReaperScanLimit = 100;
        private long orphanReconcileIntervalMs = 15_000L;
        private int orphanReconcileScanLimit = 200;
        private long orphanGraceMs = 5_000L;
        public int getLeaseTimeoutSeconds() { return leaseTimeoutSeconds; }
        public void setLeaseTimeoutSeconds(int leaseTimeoutSeconds) { this.leaseTimeoutSeconds = leaseTimeoutSeconds; }
        public int getMaintenancePoolSize() { return maintenancePoolSize; }
        public void setMaintenancePoolSize(int maintenancePoolSize) { this.maintenancePoolSize = maintenancePoolSize; }
        public long getRetryPromoterIntervalMs() { return retryPromoterIntervalMs; }
        public void setRetryPromoterIntervalMs(long retryPromoterIntervalMs) { this.retryPromoterIntervalMs = retryPromoterIntervalMs; }
        public int getRetryPromoterScanLimit() { return retryPromoterScanLimit; }
        public void setRetryPromoterScanLimit(int retryPromoterScanLimit) { this.retryPromoterScanLimit = retryPromoterScanLimit; }
        public long getLeaseReaperIntervalMs() { return leaseReaperIntervalMs; }
        public void setLeaseReaperIntervalMs(long leaseReaperIntervalMs) { this.leaseReaperIntervalMs = leaseReaperIntervalMs; }
        public int getLeaseReaperScanLimit() { return leaseReaperScanLimit; }
        public void setLeaseReaperScanLimit(int leaseReaperScanLimit) { this.leaseReaperScanLimit = leaseReaperScanLimit; }
        public long getOrphanReconcileIntervalMs() { return orphanReconcileIntervalMs; }
        public void setOrphanReconcileIntervalMs(long orphanReconcileIntervalMs) { this.orphanReconcileIntervalMs = orphanReconcileIntervalMs; }
        public int getOrphanReconcileScanLimit() { return orphanReconcileScanLimit; }
        public void setOrphanReconcileScanLimit(int orphanReconcileScanLimit) { this.orphanReconcileScanLimit = orphanReconcileScanLimit; }
        public long getOrphanGraceMs() { return orphanGraceMs; }
        public void setOrphanGraceMs(long orphanGraceMs) { this.orphanGraceMs = orphanGraceMs; }
    }

    public static final class Queue {
        private long maxDepth = 10000L;
        public long getMaxDepth() { return maxDepth; }
        public void setMaxDepth(long maxDepth) { this.maxDepth = maxDepth; }
    }

    public static final class Submission {
        private long reservationRecordWaitMs = 1_000L;
        private long admissionWaitMs = 1_000L;
        private long resolvePollIntervalMs = 25L;
        private long maxPayloadBytes = 1_048_576L;
        public long getReservationRecordWaitMs() { return reservationRecordWaitMs; }
        public void setReservationRecordWaitMs(long reservationRecordWaitMs) { this.reservationRecordWaitMs = reservationRecordWaitMs; }
        public long getAdmissionWaitMs() { return admissionWaitMs; }
        public void setAdmissionWaitMs(long admissionWaitMs) { this.admissionWaitMs = admissionWaitMs; }
        public long getResolvePollIntervalMs() { return resolvePollIntervalMs; }
        public void setResolvePollIntervalMs(long resolvePollIntervalMs) { this.resolvePollIntervalMs = resolvePollIntervalMs; }
        public long getMaxPayloadBytes() { return maxPayloadBytes; }
        public void setMaxPayloadBytes(long maxPayloadBytes) { this.maxPayloadBytes = maxPayloadBytes; }
    }

    public static final class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        private int defaultTimeoutSeconds = 30;
        private int awaitTerminationSeconds = 30;
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
        public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) { this.awaitTerminationSeconds = awaitTerminationSeconds; }
    }

    public static final class Retention {
        private boolean clearPayloadOnCompletion = false;
        private boolean storeExecutionLogs = true;
        private int maxExecutionLogsPerJob = 50;
        private int executionLogTtlDays = 7;
        public boolean isClearPayloadOnCompletion() { return clearPayloadOnCompletion; }
        public void setClearPayloadOnCompletion(boolean clearPayloadOnCompletion) { this.clearPayloadOnCompletion = clearPayloadOnCompletion; }
        public boolean isStoreExecutionLogs() { return storeExecutionLogs; }
        public void setStoreExecutionLogs(boolean storeExecutionLogs) { this.storeExecutionLogs = storeExecutionLogs; }
        public int getMaxExecutionLogsPerJob() { return maxExecutionLogsPerJob; }
        public void setMaxExecutionLogsPerJob(int maxExecutionLogsPerJob) { this.maxExecutionLogsPerJob = maxExecutionLogsPerJob; }
        public int getExecutionLogTtlDays() { return executionLogTtlDays; }
        public void setExecutionLogTtlDays(int executionLogTtlDays) { this.executionLogTtlDays = executionLogTtlDays; }
    }

    public static final class Redis {
        private String sentinelMaster;
        private List<String> sentinelNodes;
        private boolean clusterMode;
        private String password;
        private int poolMaxTotal = 64;
        private int poolMaxIdle = 16;
        private int poolMinIdle = 4;
        private long poolMaxWaitMs = 2000L;
        private long commandTimeoutMs = 5000L;
        public String getSentinelMaster() { return sentinelMaster; }
        public void setSentinelMaster(String sentinelMaster) { this.sentinelMaster = sentinelMaster; }
        public List<String> getSentinelNodes() { return sentinelNodes; }
        public void setSentinelNodes(List<String> sentinelNodes) { this.sentinelNodes = sentinelNodes; }
        public boolean isClusterMode() { return clusterMode; }
        public void setClusterMode(boolean clusterMode) { this.clusterMode = clusterMode; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getPoolMaxTotal() { return poolMaxTotal; }
        public void setPoolMaxTotal(int poolMaxTotal) { this.poolMaxTotal = poolMaxTotal; }
        public int getPoolMaxIdle() { return poolMaxIdle; }
        public void setPoolMaxIdle(int poolMaxIdle) { this.poolMaxIdle = poolMaxIdle; }
        public int getPoolMinIdle() { return poolMinIdle; }
        public void setPoolMinIdle(int poolMinIdle) { this.poolMinIdle = poolMinIdle; }
        public long getPoolMaxWaitMs() { return poolMaxWaitMs; }
        public void setPoolMaxWaitMs(long poolMaxWaitMs) { this.poolMaxWaitMs = poolMaxWaitMs; }
        public long getCommandTimeoutMs() { return commandTimeoutMs; }
        public void setCommandTimeoutMs(long commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }
    }
}
