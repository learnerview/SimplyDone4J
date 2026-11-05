package io.github.learnerview.simplydone4j.demo;

import io.github.learnerview.simplydone4j.event.JobEventPublisher;
import lombok.Builder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class DemoEventLog {

    private static final int MAX_EVENTS = 100;

    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();

    @EventListener
    public void onEvent(JobEventPublisher.JobPublishedEvent published) {
        var data = published.data();
        events.addFirst(Event.builder()
                .timestamp(Instant.now())
                .event(published.event().name())
                .jobId(data.getJobId())
                .jobType(data.getJobType())
                .status(data.getStatus())
                .attempt(data.getAttempt())
                .durationMs(data.getDurationMs())
                .build());
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    public List<Event> recent() {
        return events.stream().toList();
    }

    @Builder
    public record Event(Instant timestamp, String event, String jobId, String jobType,
                        String status, Integer attempt, Long durationMs) {
    }
}
