package com.stockflow.notification.internal.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * A transport did not deliver a message, and why in the only terms a caller can act on.
 *
 * <ul>
 *   <li>{@link Kind#RETRYABLE} - the same message may well go through later: a timeout, a 5xx, a rate limit.
 *       {@link #retryAfter()} is the delay the provider asked for, when it asked.</li>
 *   <li>{@link Kind#REJECTED} - this message will never be accepted (an invalid recipient address); retrying only
 *       spends quota and, for a provider, sender reputation.</li>
 *   <li>{@link Kind#MISCONFIGURED} - no message will be accepted until an operator fixes the configuration (a revoked
 *       API key, an unverified sender domain). Not retried either: every retry would fail the same way, and the event
 *       registry would fill with work that cannot finish. Logged at ERROR by the transport.</li>
 * </ul>
 *
 * <p>{@link #code()} is a short, stable code for {@code delivery_log.error} (for example {@code RESEND_RATE_LIMITED}).
 * The message carries the code and nothing a provider sent back: provider error texts may quote the address.</p>
 */
public class EmailDeliveryException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public enum Kind { RETRYABLE, REJECTED, MISCONFIGURED }

    private final Kind kind;
    private final String code;
    private final Duration retryAfter;

    private EmailDeliveryException(Kind kind, String code, Duration retryAfter, Throwable cause) {
        super(kind + " " + code, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = Objects.requireNonNull(code, "code");
        this.retryAfter = retryAfter;
    }

    public static EmailDeliveryException retryable(String code, Duration retryAfter, Throwable cause) {
        return new EmailDeliveryException(Kind.RETRYABLE, code, retryAfter, cause);
    }

    public static EmailDeliveryException rejected(String code, Throwable cause) {
        return new EmailDeliveryException(Kind.REJECTED, code, null, cause);
    }

    public static EmailDeliveryException misconfigured(String code, Throwable cause) {
        return new EmailDeliveryException(Kind.MISCONFIGURED, code, null, cause);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    /** The delay the provider asked for before the next attempt; null when it named none. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return kind == Kind.RETRYABLE;
    }
}
