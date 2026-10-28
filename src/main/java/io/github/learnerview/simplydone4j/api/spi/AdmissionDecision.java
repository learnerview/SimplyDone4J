package io.github.learnerview.simplydone4j.api.spi;

import java.time.Duration;

/**
 * The policy's answer.
 *
 * @param admitted    whether the submission may proceed
 * @param retryAfter  how long the caller should wait before retrying; {@code null} when
 *                    the rejection has no natural delay or the delay is unknown
 * @param reason      why the submission was rejected, for the caller and the logs;
 *                    {@code null} when the submission was admitted
 */
public record AdmissionDecision(boolean admitted, Duration retryAfter, String reason) {

    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, null, null);
    }

    public static AdmissionDecision reject(Duration retryAfter, String reason) {
        return new AdmissionDecision(false, retryAfter, reason);
    }

    public static AdmissionDecision reject(String reason) {
        return new AdmissionDecision(false, null, reason);
    }
}
