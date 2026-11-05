package io.github.learnerview.simplydone4j.demo;

import java.time.Instant;

public record OrderEvent(Instant at, OrderStage stage, String message) {
}
