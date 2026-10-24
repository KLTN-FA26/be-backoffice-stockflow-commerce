package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailAddress;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.EmailTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * E-mail through Resend's HTTP API ({@code POST /emails}), on {@code common}'s {@link RestClientFactory} - its timeouts,
 * correlation id and call log - rather than Resend's SDK: one endpoint does not justify a dependency and a second HTTP
 * stack with timeouts of its own.
 *
 * <h2>What each answer means</h2>
 * <ul>
 *   <li>2xx - accepted; the receipt carries Resend's id, which its webhooks name later.</li>
 *   <li>401, 403 - the key is missing, revoked or restricted, or the sender's domain is not verified (Resend's "testing
 *       emails only to your own address"). Every message would fail the same way: {@code MISCONFIGURED}, logged at
 *       ERROR, never retried.</li>
 *   <li>422 - the message itself is refused (an invalid recipient, a missing field); {@code invalid_from_address} is
 *       the configuration's fault and reported as such. Not retried.</li>
 *   <li>409 - an idempotency key in flight ({@code concurrent_idempotent_requests}: retry) or reused with a different
 *       message ({@code invalid_idempotent_request}: not retried - the first request with that key may have been
 *       sent, and a second e-mail is the one outcome the key exists to prevent).</li>
 *   <li>429 - rate limit or quota: retried, not before the provider's {@code Retry-After}.</li>
 *   <li>5xx, a timeout, no connection - retried.</li>
 * </ul>
 *
 * <h2>Pacing</h2>
 * <p>Resend allows a team a few requests per second, and the event listeners that send run on virtual threads with no
 * bound of their own: a load of thirty shipments handed over at once would otherwise burst thirty requests and collect
 * thirty 429s. Each instance keeps to {@code requests-per-second} and {@code max-concurrent-requests}; a caller waits
 * for its turn holding no database connection (the customer listener sends outside any transaction).</p>
 *
 * <h2>What is never logged</h2>
 * <p>The key (sent only as a header the call log does not print), subjects, bodies, addresses, and Resend's error
 * {@code message}, which may quote the address. Its error {@code name} is a fixed identifier and is logged.</p>
 */
final class ResendEmailTransport implements EmailTransport {

    static final String SERVICE = "resend";

    /** Resend's limit on {@code to}. Larger staff lists are split by the caller. */
    static final int MAX_RECIPIENTS = 50;

    /** A provider asking for more than this is treated as asking for this; the retry delay is capped anyway. */
    private static final Duration LONGEST_RETRY_AFTER = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(ResendEmailTransport.class);

    private final RestClient client;
    private final Clock clock;
    private final Semaphore inFlight;
    private final long intervalNanos;
    private long nextSlotNanos;

    ResendEmailTransport(RestClientFactory clients, EmailProperties properties, Clock clock) {
        this.clock = clock;
        EmailProperties.Resend resend = properties.resend();
        if (resend.apiKey() == null) {
            throw new IllegalStateException("RESEND_API_KEY is required when stockflow.notification.email.provider is"
                    + " resend. Set it, or choose another provider (smtp sends to Mailpit locally)");
        }
        if (EmailProperties.UNCONFIGURED_FROM.equals(properties.from())) {
            throw new IllegalStateException("RESEND_FROM is required when stockflow.notification.email.provider is"
                    + " resend: \"Name <address>\" on the domain verified in Resend");
        }
        String sender = EmailAddress.addressOf(properties.from());
        if (sender == null) {
            throw new IllegalStateException("stockflow.notification.email.from (RESEND_FROM) must be an address or"
                    + " \"Name <address>\" on the domain verified in Resend");
        }
        if (EmailAddress.reservedDomain(sender)) {
            throw new IllegalStateException("stockflow.notification.email.from (RESEND_FROM) is on a domain no one can"
                    + " verify in Resend; use an address on your own verified domain");
        }
        this.client = clients.forService(SERVICE, resend.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resend.apiKey())
                .build();
        this.inFlight = new Semaphore(resend.maxConcurrentRequests(), true);
        this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / resend.requestsPerSecond();
    }

    @Override
    public EmailProvider provider() {
        return EmailProvider.RESEND;
    }

    @Override
    public EmailReceipt send(Email email) {
        // Demo and test data use reserved domains on purpose (example.com, *.test). Delivering to them only collects
        // bounces, and a provider suspends a sender whose bounce rate climbs; they are dropped here, before the API.
        List<String> to = email.to().stream().filter(address -> !EmailAddress.reservedDomain(address)).toList();
        if (to.isEmpty()) {
            throw EmailDeliveryException.rejected("RECIPIENT_RESERVED_DOMAIN", null);
        }
        if (to.size() > MAX_RECIPIENTS) {
            throw EmailDeliveryException.rejected("RESEND_TOO_MANY_RECIPIENTS", null);
        }
        SendRequest body = new SendRequest(email.from(), to, email.subject(), email.html(), email.text(),
                email.replyTo(), email.category() == null ? null : List.of(new Tag("category", email.category())));
        acquire();
        try {
            return client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (email.idempotencyKey() != null) {
                            headers.set("Idempotency-Key", email.idempotencyKey());
                        }
                    })
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 200 && status < 300) {
                            SendResponse accepted = readOrNull(response, SendResponse.class);
                            log.debug("Resend accepted a message ({}{})", email.category(),
                                    email.idempotencyKey() == null ? "" : ", idempotent");
                            return new EmailReceipt(EmailProvider.RESEND, accepted == null ? null : accepted.id());
                        }
                        ErrorResponse error = readOrNull(response, ErrorResponse.class);
                        throw refusal(status, error == null ? null : error.name(),
                                retryAfter(response.getHeaders(), clock.instant()));
                    });
        } catch (ResourceAccessException unreachable) {
            boolean timeout = causedByTimeout(unreachable);
            log.warn("Resend {}: {}", timeout ? "timed out" : "unreachable",
                    LogSafe.text(unreachable.getMostSpecificCause().getClass().getSimpleName()));
            throw EmailDeliveryException.retryable(timeout ? "RESEND_TIMEOUT" : "RESEND_UNREACHABLE", null,
                    unreachable);
        } finally {
            inFlight.release();
        }
    }

    /** The classification the class javadoc lists. Logs once, here, at the level the outcome deserves. */
    static EmailDeliveryException refusal(int status, String name, Duration retryAfter) {
        String error = name == null ? "unnamed" : LogSafe.text(name);
        if (status == 401 || status == 403) {
            log.error("Resend refused the request (HTTP {}, {}): check RESEND_API_KEY and that the sender's domain is"
                    + " verified in Resend. The message is not retried", status, error);
            return EmailDeliveryException.misconfigured(status == 401 ? "RESEND_UNAUTHORIZED" : "RESEND_FORBIDDEN",
                    null);
        }
        if (status == 422 && "invalid_from_address".equals(name)) {
            log.error("Resend refused the sender address (HTTP 422, {}): check RESEND_FROM", error);
            return EmailDeliveryException.misconfigured("RESEND_SENDER_REJECTED", null);
        }
        if (status == 409) {
            if ("concurrent_idempotent_requests".equals(name)) {
                return EmailDeliveryException.retryable("RESEND_IDEMPOTENCY_IN_PROGRESS", Duration.ofSeconds(5), null);
            }
            log.warn("Resend refused a reused idempotency key (HTTP 409, {}): not sent again", error);
            return EmailDeliveryException.rejected("RESEND_IDEMPOTENCY_CONFLICT", null);
        }
        if (status == 429) {
            log.warn("Resend rate limit or quota (HTTP 429, {}); retrying after {}", error,
                    retryAfter == null ? "the back-off" : retryAfter);
            return EmailDeliveryException.retryable("RESEND_RATE_LIMITED", retryAfter, null);
        }
        if (status >= 500) {
            log.warn("Resend failed (HTTP {}, {}); the message will be retried", status, error);
            return EmailDeliveryException.retryable("RESEND_UNAVAILABLE", retryAfter, null);
        }
        if (status == 400 || status == 404 || status == 405 || status == 422) {
            log.warn("Resend rejected a message (HTTP {}, {}); it is not retried", status, error);
            return EmailDeliveryException.rejected("RESEND_REJECTED_" + status, null);
        }
        // A redirect or an undocumented status: nothing says it is permanent, and the attempt limit bounds it.
        log.warn("Resend answered HTTP {} ({}); the message will be retried", status, error);
        return EmailDeliveryException.retryable("RESEND_HTTP_" + status, retryAfter, null);
    }

    /**
     * {@code Retry-After} in seconds or as an HTTP date, else Resend's {@code ratelimit-reset} seconds; null when
     * neither is there or readable.
     */
    static Duration retryAfter(HttpHeaders headers, Instant now) {
        Duration asked = seconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
        if (asked == null) {
            String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (value != null) {
                try {
                    asked = Duration.between(now,
                            ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                } catch (DateTimeParseException unreadable) {
                    asked = null;
                }
            }
        }
        if (asked == null) {
            asked = seconds(headers.getFirst("ratelimit-reset"));
        }
        if (asked == null || asked.isNegative()) {
            return null;
        }
        return asked.compareTo(LONGEST_RETRY_AFTER) > 0 ? LONGEST_RETRY_AFTER : asked;
    }

    private static Duration seconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }

    private static <T> T readOrNull(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
                                    Class<T> type) {
        try {
            return response.bodyTo(type);
        } catch (RuntimeException unreadable) {
            // An answer that is not the documented JSON: the status alone decides.
            return null;
        }
    }

    private static boolean causedByTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Waits for an in-flight slot, then for this instance's next send time. Holds no lock while sleeping. */
    private void acquire() {
        try {
            inFlight.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw EmailDeliveryException.retryable("RESEND_INTERRUPTED", null, interrupted);
        }
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlotNanos);
            nextSlotNanos = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(wait);
            } catch (InterruptedException interrupted) {
                inFlight.release();
                Thread.currentThread().interrupt();
                throw EmailDeliveryException.retryable("RESEND_INTERRUPTED", null, interrupted);
            }
        }
    }

    /** Resend's request body. Absent parts are left out rather than sent as JSON nulls. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SendRequest(String from, List<String> to, String subject, String html, String text,
                       @JsonProperty("reply_to") String replyTo, List<Tag> tags) {
    }

    record Tag(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SendResponse(String id) {
    }

    /** Resend's error body. {@code message} is never read: it may quote the address. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(Integer statusCode, String name) {
    }
}
