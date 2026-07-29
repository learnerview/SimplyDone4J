package io.github.learnerview.simplydone4j.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HandlerRegistry {
    private static final Logger log = LoggerFactory.getLogger(HandlerRegistry.class);
    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    public void register(String jobType, JobHandler handler) {
        JobHandler existing = handlers.putIfAbsent(jobType, handler);
        if (existing != null) {
            log.warn("Handler already registered for jobType: {}", jobType);
        } else {
            log.info("Registered handler for jobType: {} -> {}", jobType, handler.getClass().getSimpleName());
        }
    }

    public JobHandler getHandler(String jobType) {
        JobHandler handler = handlers.get(jobType);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for jobType: " + jobType);
        }
        return handler;
    }

    public Map<String, JobHandler> getHandlers() {
        return Map.copyOf(handlers);
    }
}
