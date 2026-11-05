package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.model.EngineStatus;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import io.github.learnerview.simplydone4j.repository.JobQueryRepository;
import io.github.learnerview.simplydone4j.repository.QueueRepository;
import lombok.Builder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class DemoMetricsService {

    private static final int LOW_STOCK_THRESHOLD = 5;

    private final JobQueryRepository jobs;
    private final QueueRepository queues;
    private final DemoCatalogService catalog;
    private final SimplyDoneProperties properties;

    public DemoMetricsService(JobQueryRepository jobs, QueueRepository queues,
                              DemoCatalogService catalog, SimplyDoneProperties properties) {
        this.jobs = jobs;
        this.queues = queues;
        this.catalog = catalog;
        this.properties = properties;
    }

    public OperationsSummary summary(OrderService.OrderStats stats, EngineStatus engine,
                                     long dlqCount) {
        Map<String, Long> counts = countsByStatus();
        Map<String, Long> depths = queueDepths();
        long succeeded = counts.getOrDefault(JobStatus.SUCCESS.name(), 0L);
        long failed = counts.getOrDefault(JobStatus.FAILED.name(), 0L)
                + counts.getOrDefault(JobStatus.DLQ.name(), 0L);
        long processed = succeeded + failed;
        double completionRate = processed == 0 ? 0.0 : (double) succeeded * 100.0 / processed;

        List<LowStockProduct> lowStock = catalog.products().stream()
                .filter(product -> product.stock() <= LOW_STOCK_THRESHOLD)
                .map(product -> new LowStockProduct(product.sku(), product.name(), product.stock()))
                .toList();

        return OperationsSummary.builder()
                .jobCounts(counts)
                .queueDepths(depths)
                .orders(OrderSummary.builder()
                        .tracked(stats.tracked())
                        .active(stats.active())
                        .completed(stats.completed())
                        .cancelled(stats.cancelled())
                        .failed(stats.failed())
                        .revenue(stats.revenue())
                        .unitsShipped(stats.unitsShipped())
                        .build())
                .lowStockCount(lowStock.size())
                .lowStock(lowStock)
                .workerSlots(properties.getExecutor().getMaxPoolSize())
                .jobCompletionRate(round(completionRate))
                .engine(engine)
                .dlqCount(dlqCount)
                .queueMaxDepth(properties.getQueue().getMaxDepth())
                .maxAttempts(properties.getRetry().getMaxAttempts())
                .pollingIntervalMs(properties.getScheduler().getPollingIntervalMs())
                .generatedAt(Instant.now())
                .build();
    }

    private Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new TreeMap<>();
        for (JobStatus status : JobStatus.values()) {
            counts.put(status.name(), jobs.countByStatus(status));
        }
        return counts;
    }

    private Map<String, Long> queueDepths() {
        Map<String, Long> depths = new TreeMap<>();
        for (JobPriority priority : JobPriority.values()) {
            depths.put(priority.name(), queues.queueSize(priority));
        }
        return depths;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    @Builder
    public record OrderSummary(long tracked, long active, long completed, long cancelled,
                               long failed, long revenue, long unitsShipped) {
    }

    public record LowStockProduct(String sku, String name, int stock) {
    }

    @Builder
    public record OperationsSummary(Map<String, Long> jobCounts,
                                    Map<String, Long> queueDepths,
                                    OrderSummary orders,
                                    int lowStockCount,
                                    List<LowStockProduct> lowStock,
                                    int workerSlots,
                                    double jobCompletionRate,
                                    EngineStatus engine,
                                    long dlqCount,
                                    long queueMaxDepth,
                                    int maxAttempts,
                                    long pollingIntervalMs,
                                    Instant generatedAt) {
    }
}
