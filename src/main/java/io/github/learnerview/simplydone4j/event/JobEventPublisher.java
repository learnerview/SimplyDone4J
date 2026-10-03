package io.github.learnerview.simplydone4j.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Objects;

public final class JobEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(JobEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public JobEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * Notifies listeners, never propagating their failures.
     *
     * <p>{@code ApplicationEventPublisher} is synchronous, so a listener that throws throws
     * back into the caller. Every {@code publish} site here sits <em>after</em> the state
     * transition it reports, which turned any broken listener into a correctness problem:
     * a {@code JOB_CREATED} listener could make {@code submit} throw to the caller after the
     * job was already persisted and enqueued; a {@code JOB_COMPLETED} listener silently
     * skipped the success webhook; a {@code JOB_STARTED} listener threw on the
     * {@code @Scheduled} thread and aborted the poll loop.
     *
     * <p>An observer must not be able to change the thing it observes, so the failure is
     * logged and dropped -- the same treatment the execution log and the queue-depth sampler
     * already give their best-effort side channels.</p>
     */
    public void publish(JobEvent event, JobEventData data) {
        try {
            eventPublisher.publishEvent(new JobPublishedEvent(this, event, data));
        } catch (RuntimeException e) {
            log.warn("A listener threw handling {} for job {}; the job's recorded outcome is "
                    + "unaffected", event, data.getJobId(), e);
        }
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
