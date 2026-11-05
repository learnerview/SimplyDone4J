package io.github.learnerview.simplydone4j.demo;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workload")
public class DemoWorkloadController {

    private final DemoWorkloadService workload;

    public DemoWorkloadController(DemoWorkloadService workload) {
        this.workload = workload;
    }

    @PostMapping("/jobs")
    public DemoWorkloadService.JobBatchResult jobs(
            @Valid @RequestBody DemoWorkloadService.JobBatchRequest request) {
        return workload.submitJobs(request);
    }

    @PostMapping("/orders")
    public DemoWorkloadService.OrderBatchResult orders(
            @Valid @RequestBody DemoWorkloadService.OrderBatchRequest request) {
        return workload.submitOrders(request);
    }
}
