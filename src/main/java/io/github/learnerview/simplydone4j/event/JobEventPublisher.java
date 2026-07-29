package io.github.learnerview.simplydone4j.event;

import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;

public final class JobEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    public JobEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public void publish(JobEvent event, JobEventData data) {
        eventPublisher.publishEvent(new JobPublishedEvent(this, event, data));
    }

    public record JobPublishedEvent(Object source, JobEvent event, JobEventData data) {}
}
