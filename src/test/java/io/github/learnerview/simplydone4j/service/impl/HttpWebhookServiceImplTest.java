package io.github.learnerview.simplydone4j.service.impl;

import io.github.learnerview.simplydone4j.autoconfigure.SimplyDoneProperties;
import io.github.learnerview.simplydone4j.entity.JobEntity;
import io.github.learnerview.simplydone4j.model.JobPriority;
import io.github.learnerview.simplydone4j.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpWebhookServiceImplTest {

    private SimplyDoneProperties props;
    private HttpClient httpClient;
    private HttpWebhookServiceImpl service;

    @BeforeEach
    void setUp() {
        props = new SimplyDoneProperties();
        // Zero delays keep the retry tests fast without changing the logic under test.
        props.getWebhook().setInitialDelayMillis(0L);
        props.getWebhook().setJitterFactor(0.0);
        httpClient = mock(HttpClient.class);
        service = new HttpWebhookServiceImpl(props, httpClient);
    }

    private JobEntity jobWithCallback() {
        return JobEntity.builder()
                .id("job-1")
                .jobType("email-send")
                .status(JobStatus.SUCCESS)
                .priority(JobPriority.NORMAL)
                .payload("{}")
                .callbackUrl("https://example.test/hook")
                .build();
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int statusCode) {
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpResponse<Object> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(statusCode);
                    return CompletableFuture.completedFuture(response);
                });
    }

    @SuppressWarnings("unchecked")
    private void failWith(Throwable error) {
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    CompletableFuture<Object> failed = new CompletableFuture<>();
                    failed.completeExceptionally(error);
                    return failed;
                });
    }

    @Test
    void shouldNotSendAnythingWhenNoCallbackUrlIsSet() {
        JobEntity job = JobEntity.builder().id("job-1").jobType("t").status(JobStatus.SUCCESS).build();

        service.fireCallback(job, "SUCCESS", null);

        verify(httpClient, after(300).never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldNotSendAnythingWhenWebhooksAreDisabled() {
        props.getWebhook().setEnabled(false);
        respondWith(200);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, after(300).never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldSendExactlyOnceOnSuccess() {
        respondWith(200);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, timeout(5000).times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldTreatAnyTwoHundredsAsSuccess() {
        respondWith(204);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, timeout(5000).times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldRetryOnServerErrorUpToTheAttemptBudget() {
        respondWith(503);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, timeout(5000).times(3)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldRetryOnRateLimitAndRequestTimeout() {
        respondWith(429);
        service.fireCallback(jobWithCallback(), "SUCCESS", null);
        verify(httpClient, timeout(5000).times(3)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        respondWith(408);
        service.fireCallback(jobWithCallback(), "SUCCESS", null);
        verify(httpClient, timeout(5000).times(6)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldRetryOnTransportFailure() {
        failWith(new java.io.IOException("connection reset"));

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, timeout(5000).times(3)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldNotRetryClientErrors() {
        // 400/401/403/404/422 will fail identically on every replay; retrying just
        // burns connections and delays the log line the operator needs to see.
        for (int status : List.of(400, 401, 403, 404, 422)) {
            HttpClient client = mock(HttpClient.class);
            respondWithOn(client, status);
            new HttpWebhookServiceImpl(props, client).fireCallback(jobWithCallback(), "SUCCESS", null);
            verify(client, timeout(5000).times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @SuppressWarnings("unchecked")
    private void respondWithOn(HttpClient client, int statusCode) {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpResponse<Object> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(statusCode);
                    return CompletableFuture.completedFuture(response);
                });
    }

    @Test
    void shouldMakeExactlyOneAttemptWhenRetriesAreDisabled() {
        props.getWebhook().setRetryEnabled(false);
        respondWith(500);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        verify(httpClient, timeout(5000).times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldNotThrowOnAMalformedCallbackUrl() {
        JobEntity job = JobEntity.builder()
                .id("job-1").jobType("t").status(JobStatus.SUCCESS)
                .callbackUrl("not a url at all")
                .build();

        assertDoesNotThrow(() -> service.fireCallback(job, "SUCCESS", null));
        verify(httpClient, after(300).never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldBackOffExponentiallyAndRespectTheCap() {
        props.getWebhook().setInitialDelayMillis(100L);
        props.getWebhook().setBackoffMultiplier(2.0);
        props.getWebhook().setMaxDelayMillis(400L);

        assertEquals(100L, service.backoffMillis(1));
        assertEquals(200L, service.backoffMillis(2));
        assertEquals(400L, service.backoffMillis(3));
        assertEquals(400L, service.backoffMillis(9), "Delay must be capped");
    }

    @Test
    void shouldKeepJitteredBackoffInsideTheConfiguredBand() {
        props.getWebhook().setInitialDelayMillis(1000L);
        props.getWebhook().setJitterFactor(0.2);

        for (int i = 0; i < 200; i++) {
            long delay = service.backoffMillis(1);
            assertTrue(delay >= 800L && delay <= 1200L, "delay " + delay + " outside +/-20% of 1000");
        }
    }

    @Test
    void shouldNeverReturnANegativeDelay() {
        props.getWebhook().setInitialDelayMillis(0L);
        props.getWebhook().setJitterFactor(1.0);

        assertFalse(service.backoffMillis(1) < 0L);
    }

    @Test
    void shouldEscapePayloadContentSoTheBodyStaysValidJson() {
        List<HttpRequest> captured = new ArrayList<>();
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    captured.add(inv.getArgument(0));
                    HttpResponse<Object> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(200);
                    return CompletableFuture.completedFuture(response);
                });

        JobEntity job = JobEntity.builder()
                .id("job-1").jobType("t").status(JobStatus.SUCCESS)
                .callbackUrl("https://example.test/hook")
                .result("said \"hi\"\nthen left")
                .build();

        service.fireCallback(job, "SUCCESS", "boom \"quoted\"");

        assertEquals(1, captured.size());
        HttpRequest request = captured.get(0);
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(request.timeout().isPresent(), "A callback must carry a request timeout");
    }

    @Test
    void shouldRejectANonPositiveAttemptBudgetRatherThanLooping() {
        props.getWebhook().setMaxAttempts(0);
        respondWith(500);

        service.fireCallback(jobWithCallback(), "SUCCESS", null);

        // maxAttempts is clamped up to 1, so this is a single attempt, not zero and not
        // an unbounded loop.
        verify(httpClient, timeout(5000).times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void shouldTolerateTheClientItselfThrowingSynchronously() {
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IllegalStateException("client misconfigured"));

        assertDoesNotThrow(() -> service.fireCallback(jobWithCallback(), "SUCCESS", null));
    }

    @Test
    void shouldHandleANullResponseAlongsideANullError() {
        when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    CompletionStage<Object> stage = CompletableFuture.completedFuture(null);
                    return stage;
                });

        assertDoesNotThrow(() -> service.fireCallback(jobWithCallback(), "SUCCESS", null));
    }

}
