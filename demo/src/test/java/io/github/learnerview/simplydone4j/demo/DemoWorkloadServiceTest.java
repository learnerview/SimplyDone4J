package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.dto.JobSubmissionRequest;
import io.github.learnerview.simplydone4j.dto.JobSubmissionResponse;
import io.github.learnerview.simplydone4j.service.JobSubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoWorkloadServiceTest {

    @Mock JobSubmissionService submissionService;
    @Mock OrderService orderService;
    @Mock DemoCatalogService catalog;

    DemoWorkloadService workload;

    @BeforeEach
    void setUp() {
        workload = new DemoWorkloadService(submissionService, orderService, catalog);
    }

    @Test
    void shouldSubmitEveryJobWithAUniqueKeyAndOrderContext() {
        when(submissionService.submit(eq("workload"), any())).thenReturn(submission("job", "QUEUED"));

        DemoWorkloadService.JobBatchResult result = workload.submitJobs(
                new DemoWorkloadService.JobBatchRequest(
                        "process-order", 2, "HIGH", Map.of("customer", "Ada"), 3, 10, 0, 0));

        assertEquals(2, result.requested());
        assertEquals(2, result.accepted());
        assertEquals(0, result.failed());

        ArgumentCaptor<JobSubmissionRequest> captor = ArgumentCaptor.forClass(JobSubmissionRequest.class);
        verify(submissionService, org.mockito.Mockito.times(2)).submit(eq("workload"), captor.capture());
        List<JobSubmissionRequest> requests = captor.getAllValues();
        assertTrue(requests.get(0).getIdempotencyKey().startsWith("workload-"));
        assertTrue(requests.get(1).getIdempotencyKey().startsWith("workload-"));
        assertTrue(!requests.get(0).getIdempotencyKey().equals(requests.get(1).getIdempotencyKey()));
        assertTrue(requests.get(0).getPayload().containsKey("orderId"));
        assertEquals("Ada", requests.get(0).getPayload().get("customer"));
    }

    @Test
    void shouldReportOneFailureWithoutStoppingTheBatch() {
        when(submissionService.submit(eq("workload"), any()))
                .thenThrow(new RuntimeException("redis timeout"))
                .thenReturn(submission("job-2", "QUEUED"));

        DemoWorkloadService.JobBatchResult result = workload.submitJobs(
                new DemoWorkloadService.JobBatchRequest(
                        "process-order", 2, "NORMAL", Map.of(), null, null, 0, 0));

        assertEquals(1, result.accepted());
        assertEquals(1, result.failed());
        assertEquals(0, result.failures().getFirst().index());
        assertEquals("redis timeout", result.failures().getFirst().message());
    }

    @Test
    void shouldPlaceEveryOrderAndReportAcceptedCount() {
        DemoCatalogService.Product product = new DemoCatalogService.Product(
                "cloud-runner", "Cloud Runner shoes", 129, "Sportswear", 20);
        when(catalog.products()).thenReturn(List.of(product));
        when(catalog.exists("cloud-runner")).thenReturn(true);
        when(catalog.require("cloud-runner")).thenReturn(product);
        when(orderService.place(any())).thenReturn(order());

        DemoWorkloadService.OrderBatchResult result = workload.submitOrders(
                new DemoWorkloadService.OrderBatchRequest(2, "cloud-runner", 1, "NORMAL", false));

        assertEquals(2, result.requested());
        assertEquals(2, result.accepted());
        assertEquals(0, result.failed());
        assertEquals(2, result.orders().size());
    }

    private JobSubmissionResponse submission(String id, String status) {
        return JobSubmissionResponse.builder()
                .jobId(id)
                .status(status)
                .jobType("process-order")
                .priority("NORMAL")
                .scheduledAt(Instant.now())
                .build();
    }

    private OrderRecord order() {
        Instant now = Instant.now();
        return OrderRecord.builder()
                .orderId("ORD-1")
                .customer("Ada")
                .sku("cloud-runner")
                .productName("Cloud Runner shoes")
                .quantity(1)
                .unitPrice(129)
                .total(129)
                .priority("NORMAL")
                .status(OrderStatus.ACCEPTED)
                .stage(OrderStage.INVENTORY_RESERVED)
                .createdAt(now)
                .updatedAt(now)
                .timeline(List.of())
                .build();
    }
}
