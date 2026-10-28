package io.github.learnerview.simplydone4j.api;

import io.github.learnerview.simplydone4j.handler.HandlerRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Keeps the README's Quick Start compiling against the public API.
 *
 * <p>Documentation drifts silently unless something compiles it. This test mirrors the
 * snippet: if the snippet is wrong, this file stops compiling.</p>
 */
class ReadmeQuickStartCompileTest {

    @Test
    void quickStartSnippetCompiles() {
        // The snippet lives in the helper below. Compiling this test is the assertion;
        // executing the helper would need a live Spring context.
        assertNotNull(JobClient.class);
    }

    @SuppressWarnings("unused")
    private JobHandle quickStart(HandlerRegistry registry, JobClient jobClient) {
        registry.register("email", ctx -> "email-sent-" + ctx.getJobId());

        return jobClient.submit("email",
                Map.of("to", "user@example.com"),
                opts -> opts.producer("my-app").idempotencyKey("order-123"));
    }
}
