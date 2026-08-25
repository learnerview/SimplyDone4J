package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class HttpWebhookServiceImpl implements WebhookService {
    private static final Logger log = LoggerFactory.getLogger(HttpWebhookServiceImpl.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @Override
    public void fireCallback(JobEntity job, String outcome, String errorMessage) {
        String callbackUrl = job.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) return;

        try {
            String body = "{\"jobId\":\"" + escapeJson(job.getId())
                    + "\",\"status\":\"" + outcome
                    + "\",\"jobType\":\"" + escapeJson(job.getJobType())
                    + "\",\"result\":" + (job.getResult() != null ? "\"" + escapeJson(job.getResult()) + "\"" : "null")
                    + (errorMessage != null ? ",\"error\":\"" + escapeJson(errorMessage) + "\"" : "")
                    + "}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(callbackUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(ex -> {
                        log.warn("Callback failed for job {} to {}: {}", job.getId(), callbackUrl, ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Failed to fire callback for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
