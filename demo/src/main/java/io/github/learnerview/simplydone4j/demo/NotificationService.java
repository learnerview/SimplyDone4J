package io.github.learnerview.simplydone4j.demo;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
public class NotificationService {

    private final ConcurrentLinkedDeque<Notification> notifications = new ConcurrentLinkedDeque<>();

    public void record(String type, String message) {
        notifications.addFirst(new Notification(Instant.now(), type, message));
        while (notifications.size() > 50) {
            notifications.removeLast();
        }
    }

    public List<Notification> recent() {
        return notifications.stream().toList();
    }

    public record Notification(Instant timestamp, String type, String message) {
    }
}
