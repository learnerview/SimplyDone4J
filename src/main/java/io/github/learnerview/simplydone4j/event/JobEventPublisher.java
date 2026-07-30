package io.github.learnerview.simplydone4j.event;

import org.springframework.context.ApplicationEvent;
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

    /**
     * Spring {@link ApplicationEvent} published on every job lifecycle transition.
     * Listen with {@code @EventListener(JobPublishedEvent.class)}.
     */
    public static final class JobPublishedEvent extends ApplicationEvent {
        private static final long serialVersionUID = 1L;
        private final JobEvent event;
        private final JobEventData data;

        public JobPublishedEvent(Object source, JobEvent event, JobEventData data) {
            super(source);
            this.event = Objects.requireNonNull(event, "event");
            this.data = Objects.requireNonNull(data, "data");
        }

        public JobEvent event() { return event; }
        public JobEventData data() { return data; }
    }
}
