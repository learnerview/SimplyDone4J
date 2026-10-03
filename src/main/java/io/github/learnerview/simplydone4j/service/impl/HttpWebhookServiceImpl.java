package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Fire-and-forget HTTP callback for job outcomes.
 *
 * <p>Delivery is best-effort by contract. The job has already been persisted in its
 * terminal state before this runs, so a callback that never arrives must not be able to
 * change that. Nothing here throws into the job path: every failure is logged and
 * dropped, and the terminal write is never rolled back because a webhook 500'd.</p>
 *
 * <p>Retries are scheduled onto {@link CompletableFuture#delayedExecutor}, so a backing-off
 * callback occupies no thread and needs no scheduler of its own. Only failures that can
 * plausibly succeed on a second attempt are retried:</p>
 * <ul>
 *   <li>transport failures and timeouts - the request may never have been received;</li>
 *   <li>{@code 408} and {@code 429} - the server explicitly asked us to come back;</li>
 *   <li>{@code 5xx} - the server failed, not the request.</li>
 * </ul>
 *
 * <p>Other {@code 4xx} responses are terminal: replaying a request the server has already
 * rejected as malformed or forbidden wastes a connection and, on a well-behaved endpoint,
 * keeps failing identically every time.</p>
 */
public class HttpWebhookServiceImpl implements WebhookService {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookServiceImpl.class);

    private final SimplyDoneProperties config;
    private final HttpClient httpClient;

    public HttpWebhookServiceImpl(SimplyDoneProperties config) {
        this(config, null);
    }

    /**
     * @param httpClient injectable for tests; when null a client is built from
     *                   {@code simplydone4j.webhook.connect-timeout-millis}.
     */
    public HttpWebhookServiceImpl(SimplyDoneProperties config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getWebhook().getConnectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void fireCallback(JobEntity job, String outcome, String errorMessage) {
        if (!config.getWebhook().isEnabled()) return;

        String callbackUrl = job.getCallbackUrl();
        if (callbackUrl == null || callbackUrl.isBlank()) return;

        final String body;
        try {
            body = buildBody(job, outcome, errorMessage);
        } catch (RuntimeException e) {
            log.warn("Could not build callback body for job {}: {}", job.getId(), e.getMessage());
            return;
        }

        int maxAttempts = config.getWebhook().isRetryEnabled()
                ? Math.max(1, config.getWebhook().getMaxAttempts()) : 1;

        try {
            attempt(callbackUrl, body, job.getId(), 1, maxAttempts);
        } catch (RuntimeException e) {
            // sendAsync can throw synchronously -- an already-rejected executor, or a
            // request the client rejects on the spot. The job is already in its terminal
            // state, so this must be swallowed here rather than reach the worker thread
            // that is in the middle of recording the outcome.
            log.warn("Could not dispatch callback for job {}: {}", job.getId(), e.getMessage());
        }
    }

    private void attempt(String url, String body, String jobId, int attempt, int maxAttempts) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(config.getWebhook().getRequestTimeoutMillis()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (RuntimeException e) {
            // A malformed URL will not become well-formed on a retry.
            log.warn("Invalid callback URL for job {}: {}", jobId, e.getMessage());
            return;
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> onAttemptComplete(url, body, jobId, attempt, maxAttempts, response, error));
    }

    private void onAttemptComplete(String url, String body, String jobId, int attempt, int maxAttempts,
                                   HttpResponse<?> response, Throwable error) {
        if (error == null && response != null && isSuccess(response.statusCode())) {
            if (attempt > 1) {
                log.info("Callback for job {} delivered on attempt {}", jobId, attempt);
            }
            return;
        }

        boolean retryable = error != null || (response != null && isRetryableStatus(response.statusCode()));
        if (!retryable || attempt >= maxAttempts) {
            log.warn("Callback for job {} to {} failed permanently after {} attempt(s): {}",
                    jobId, url, attempt, describe(response, error));
            return;
        }

        long delayMs = backoffMillis(attempt);
        log.warn("Callback for job {} to {} failed ({}); retrying in {}ms",
                jobId, url, describe(response, error), delayMs);
        try {
            CompletableFuture
                    .runAsync(() -> attempt(url, body, jobId, attempt + 1, maxAttempts),
                            CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS))
                    .exceptionally(ex -> {
                        log.warn("Could not schedule callback retry for job {}: {}", jobId, ex.getMessage());
                        return null;
                    });
        } catch (RuntimeException e) {
            // A rejected scheduler must not surface into the caller's terminal write.
            log.warn("Could not schedule callback retry for job {}: {}", jobId, e.getMessage());
        }
    }

    /**
     * Exponential backoff with the same positive-jitter treatment as job retries: a fleet
     * of jobs finishing together must not replay in lockstep against an endpoint that is
     * already struggling.
     */
    long backoffMillis(int attempt) {
        SimplyDoneProperties.Webhook webhook = config.getWebhook();
        double raw = webhook.getInitialDelayMillis()
                * Math.pow(webhook.getBackoffMultiplier(), Math.max(0, attempt - 1));
        double capped = Math.min(raw, webhook.getMaxDelayMillis());
        if (webhook.getJitterFactor() <= 0) {
            return (long) capped;
        }
        double jitterRange = capped * webhook.getJitterFactor();
        double jitter = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterRange;
        return Math.max(0L, (long) (capped + jitter));
    }

    private static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static String describe(HttpResponse<?> response, Throwable error) {
        if (error != null) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        if (response == null) {
            return "no response";
        }
        return "HTTP " + response.statusCode();
    }

    private static String buildBody(JobEntity job, String outcome, String errorMessage) {
        return "{\"jobId\":\"" + escapeJson(job.getId())
                + "\",\"status\":\"" + escapeJson(outcome)
                + "\",\"jobType\":\"" + escapeJson(job.getJobType())
                + "\",\"result\":" + (job.getResult() != null ? "\"" + escapeJson(job.getResult()) + "\"" : "null")
                + (errorMessage != null ? ",\"error\":\"" + escapeJson(errorMessage) + "\"" : "")
                + "}";
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
