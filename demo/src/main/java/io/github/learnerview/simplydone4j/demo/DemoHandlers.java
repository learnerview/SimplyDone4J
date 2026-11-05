package io.github.learnerview.simplydone4j.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import io.github.learnerview.simplydone4j.handler.JobContext;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DemoHandlers {

    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();

    private final HandlerRegistry registry;
    private final ObjectMapper objectMapper;
    private final OrderFulfillmentService fulfillmentService;

    public DemoHandlers(HandlerRegistry registry, ObjectMapper objectMapper,
                        OrderFulfillmentService fulfillmentService) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.fulfillmentService = fulfillmentService;
    }

    @PostConstruct
    void register() {
        registry.register("echo", this::echo);
        registry.register("slow", this::slow);
        registry.register("flaky", this::flaky);
        registry.register("always-fail", this::alwaysFail);
        registry.register("dlq-demo", this::failOnceThenSucceed);
        registry.register("timeout", this::timeout);
        registry.register("process-order", this::processOrder);
        registry.register("fulfill-product-order", fulfillmentService::fulfill);
        registry.register("reserve-inventory", this::reserveInventory);
        registry.register("charge-payment", this::chargePayment);
        registry.register("fulfill-order", this::fulfillOrder);
    }

    private String echo(JobContext context) {
        return context.getPayload() == null ? "" : context.getPayload();
    }

    private String slow(JobContext context) throws InterruptedException {
        long millis = longValue(context, "millis", 1500L);
        Thread.sleep(millis);
        return "Slept " + millis + "ms on attempt " + context.getAttemptCount();
    }

    private String flaky(JobContext context) {
        int failures = intValue(context, "failures", 2);
        if (context.getAttemptCount() < failures) {
            throw new IllegalStateException("Simulated failure " + (context.getAttemptCount() + 1)
                    + " of " + failures);
        }
        return "Recovered after " + context.getAttemptCount() + " failed attempt(s)";
    }

    private String alwaysFail(JobContext context) {
        throw new IllegalStateException("This handler always fails");
    }

    private String failOnceThenSucceed(JobContext context) {
        if (failedOnce.remove(context.getJobId())) {
            return "Provider recovered after replay for job " + context.getJobId();
        }
        failedOnce.add(context.getJobId());
        throw new IllegalStateException("Simulated provider outage; replay this DLQ job to recover");
    }

    private String timeout(JobContext context) throws InterruptedException {
        long millis = longValue(context, "millis", 5000L);
        Thread.sleep(millis);
        return "This result should never be persisted";
    }

    private String processOrder(JobContext context) {
        JsonNode payload = payload(context);
        String orderId = textValue(payload, "orderId", "unknown-order");
        String customer = textValue(payload, "customer", "customer");
        return "Order " + orderId + " accepted for " + customer;
    }

    private String reserveInventory(JobContext context) throws InterruptedException {
        long millis = longValue(context, "millis", 400L);
        Thread.sleep(millis);
        JsonNode payload = payload(context);
        return "Inventory reserved for order " + textValue(payload, "orderId", "unknown-order");
    }

    private String chargePayment(JobContext context) {
        int failures = intValue(context, "failures", 0);
        if (context.getAttemptCount() < failures) {
            throw new IllegalStateException("Payment gateway temporarily unavailable");
        }
        JsonNode payload = payload(context);
        return "Payment captured for order " + textValue(payload, "orderId", "unknown-order");
    }

    private String fulfillOrder(JobContext context) throws InterruptedException {
        long millis = longValue(context, "millis", 1500L);
        Thread.sleep(millis);
        JsonNode payload = payload(context);
        return "Fulfillment queued for order " + textValue(payload, "orderId", "unknown-order");
    }

    private long longValue(JobContext context, String field, long fallback) {
        JsonNode node = payload(context);
        return node != null && node.has(field) ? node.get(field).asLong(fallback) : fallback;
    }

    private int intValue(JobContext context, String field, int fallback) {
        JsonNode node = payload(context);
        return node != null && node.has(field) ? node.get(field).asInt(fallback) : fallback;
    }

    private String textValue(JsonNode node, String field, String fallback) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }

    private JsonNode payload(JobContext context) {
        if (context.getPayload() == null || context.getPayload().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(context.getPayload());
        } catch (Exception ignored) {
            return null;
        }
    }
}
