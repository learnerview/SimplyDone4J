package io.github.learnerview.simplydone4j.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandlerRegistryTest {

    @Test
    void shouldRegisterAndRetrieveHandler() {
        HandlerRegistry registry = new HandlerRegistry();
        JobHandler handler = context -> {};
        registry.register("test", handler);
        assertSame(handler, registry.getHandler("test"));
    }

    @Test
    void shouldThrowWhenNoHandlerRegistered() {
        HandlerRegistry registry = new HandlerRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.getHandler("unknown"));
    }

    @Test
    void shouldReturnAllHandlers() {
        HandlerRegistry registry = new HandlerRegistry();
        registry.register("a", context -> {});
        registry.register("b", context -> {});
        assertEquals(2, registry.getHandlers().size());
    }
}
