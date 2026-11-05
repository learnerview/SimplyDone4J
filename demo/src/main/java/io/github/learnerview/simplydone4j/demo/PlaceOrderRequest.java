package io.github.learnerview.simplydone4j.demo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PlaceOrderRequest(
        @NotBlank @Size(max = 80) String customer,
        @NotBlank String sku,
        @Min(1) @Max(20) int quantity,
        @Pattern(regexp = "(?i)HIGH|NORMAL|LOW", message = "priority must be HIGH, NORMAL, or LOW")
        String priority,
        boolean simulatePaymentRetry) {
}
