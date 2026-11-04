package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailAddress;
import com.stockflow.notification.internal.domain.EmailDeliveryException;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.EmailTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Delivers one customer message, once, and records what happened (SCRUM-352).
 *
 * <pre>
 * 1. state         already sent, or settled (skipped, refused, given up)?  -> nothing to do
 * 2. switch        customer mail off?                                     -> SKIPPED  CUSTOMER_EMAIL_DISABLED
 * 3. addressee     CustomerDirectory, read now (never carried on the event)
 *                  no customer / no address / malformed address          -> SKIPPED  NO_RECIPIENT / RECIPIENT_MISSING / RECIPIENT_INVALID
 * 4. render        a template that cannot render is a bug, not an outage -> FAILED, final  RENDER_FAILED
 * 5. send          idempotency key = the message's reference
 *      accepted                                                           -> SENT (+ provider id)
 *      refused for this message or for every message (bad key)          -> FAILED, final, the transport's code
 *      worth retrying, attempts left                                      -> FAILED, next attempt at, and THROWS
 *      worth retrying, attempts used up                                   -> FAILED, final
 * </pre>
 *
 * <h2>Why a failure worth retrying throws, and nothing else does</h2>
 *
 * <p>The listener's event publication stays incomplete exactly when this throws, and an incomplete publication is
 * what the retry job ({@link CustomerEmailRetry}) and a restart pick up again. Everything final returns normally, so
 * its publication completes: a customer with no address must not keep an event in the registry for ever.</p>
 *
 * <h2>Exactly once, as far as it can be had</h2>
 *
 * <p>The reference is checked first, the success row's unique {@code external_reference} decides between two
 * concurrent deliveries, and the provider's idempotency key covers the one gap left: the message went out and the
 * process died before the row was written - the next attempt sends the same key and Resend answers with the message it
 * already sent instead of a second one. Over SMTP that gap remains; SMTP is a laptop's transport.</p>
 *
 * <p>No transaction here: the provider is called holding no database connection ({@link CustomerEmailLog}).</p>
 */
@Component
class CustomerEmailDelivery {

    enum Outcome { SENT, ALREADY_SENT, ALREADY_SETTLED, SKIPPED, REFUSED, GAVE_UP }

    private static final Logger log = LoggerFactory.getLogger(CustomerEmailDelivery.class);

    private final CustomerEmailLog deliveries;
    private final CustomerDirectory customers;
    private final EmailTemplates templates;
    private final EmailTransport transport;
    private final EmailProperties properties;
    private final Clock clock;

    CustomerEmailDelivery(CustomerEmailLog deliveries, CustomerDirectory customers, EmailTemplates templates,
                          EmailTransport transport, EmailProperties properties, Clock clock) {
        this.deliveries = deliveries;
        this.customers = customers;
        this.templates = templates;
        this.transport = transport;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws CustomerEmailDeferred when the attempt failed in a way worth retrying and attempts are left; the attempt
     *                               is recorded, with the time before which it is not retried
     */
    Outcome deliver(CustomerMessage message) {
        String reference = message.reference();
        CustomerEmailLog.State state = deliveries.state(reference);
        if (state.sent()) {
            return Outcome.ALREADY_SENT;
        }
        if (state.settled()) {
            return Outcome.ALREADY_SETTLED;
        }
        if (!properties.customer().enabled()) {
            return skip(message, message.recipientLabel(), "CUSTOMER_EMAIL_DISABLED");
        }

        Optional<CustomerContact> contact = message.customerId() == null ? Optional.empty()
                : customers.contact(message.customerId());
        String raw = message.customerId() == null ? message.guestAddress() : contact.map(CustomerContact::email)
                .orElse(null);
        if (message.customerId() != null && contact.isEmpty()) {
            return skip(message, message.recipientLabel(), "CUSTOMER_NOT_FOUND");
        }
        if (raw == null || raw.isBlank()) {
            return skip(message, message.recipientLabel(),
                    message.customerId() == null ? "NO_RECIPIENT" : "RECIPIENT_MISSING");
        }
        Optional<String> address = EmailAddress.usable(raw);
        if (address.isEmpty()) {
            return skip(message, message.recipientLabel(), "RECIPIENT_INVALID");
        }
        String recipient = address.get();
        int attempt = state.failures() + 1;

        EmailTemplates.Rendered rendered;
        try {
            rendered = templates.render(message.draft().write(contact.map(CustomerContact::name).orElse(null)),
                    properties.locale());
        } catch (RuntimeException broken) {
            // Deterministic: the same event renders the same way on every retry, so retrying only repeats the bug.
            log.error("Customer e-mail {} could not be rendered ({}); it is not retried", reference,
                    broken.getClass().getSimpleName(), broken);
            deliveries.failed(message, recipient, "RENDER_FAILED", attempt, null);
            return Outcome.GAVE_UP;
        }

        EmailReceipt receipt;
        try {
            receipt = transport.send(new Email(properties.from(), List.of(recipient), properties.replyTo(),
                    rendered.subject(), rendered.text(), rendered.html(), reference, message.category()));
        } catch (EmailDeliveryException failed) {
            return failed(message, recipient, attempt, failed.code(), failed.retryable(), failed.retryAfter());
        } catch (RuntimeException unexpected) {
            // Not the transport's contract: a bug or a library surprise. Bounded by the attempt limit like a 5xx.
            log.warn("Customer e-mail {}: the transport failed unexpectedly ({})", reference,
                    unexpected.getClass().getSimpleName());
            return failed(message, recipient, attempt, "TRANSPORT_ERROR", true, null);
        }
        try {
            deliveries.sent(message, recipient, receipt, attempt);
        } catch (DataIntegrityViolationException concurrent) {
            // Another delivery of the same message recorded it first; the provider's idempotency key made both one.
            log.debug("Customer e-mail {} was recorded by a concurrent delivery", reference);
            return Outcome.ALREADY_SENT;
        }
        log.info("Customer e-mail {} sent through {} (attempt {})", reference, receipt.provider(), attempt);
        return Outcome.SENT;
    }

    private Outcome failed(CustomerMessage message, String recipient, int attempt, String code, boolean retryable,
                           Duration retryAfter) {
        String reference = message.reference();
        if (!retryable) {
            deliveries.failed(message, recipient, code, attempt, null);
            log.warn("Customer e-mail {} refused ({}); it is not retried", reference, code);
            return Outcome.REFUSED;
        }
        if (attempt >= properties.customer().maxAttempts()) {
            deliveries.failed(message, recipient, code, attempt, null);
            log.warn("Customer e-mail {} given up after {} attempts ({})", reference, attempt, code);
            return Outcome.GAVE_UP;
        }
        Instant retryAt = clock.instant().plus(delay(attempt, retryAfter));
        deliveries.failed(message, recipient, code, attempt, retryAt);
        log.warn("Customer e-mail {} failed ({}, attempt {} of {}); retried from {}", reference, code, attempt,
                properties.customer().maxAttempts(), retryAt);
        throw new CustomerEmailDeferred(reference, code, attempt);
    }

    /**
     * The wait before attempt {@code failedAttempt + 1}: the first retry delay, doubled per further failure, at least
     * what the provider asked for, at most the configured maximum.
     */
    Duration delay(int failedAttempt, Duration retryAfter) {
        EmailProperties.Customer policy = properties.customer();
        Duration backoff = policy.firstRetryDelay();
        for (int i = 1; i < failedAttempt && backoff.compareTo(policy.maxRetryDelay()) < 0; i++) {
            backoff = backoff.multipliedBy(2);
        }
        if (retryAfter != null && retryAfter.compareTo(backoff) > 0) {
            backoff = retryAfter;
        }
        return backoff.compareTo(policy.maxRetryDelay()) > 0 ? policy.maxRetryDelay() : backoff;
    }

    private Outcome skip(CustomerMessage message, String recipient, String reason) {
        deliveries.skipped(message, recipient, reason);
        log.info("Customer e-mail {} not sent: {}", message.reference(), reason);
        return Outcome.SKIPPED;
    }

    /**
     * An attempt failed and will be retried; thrown so the event publication stays incomplete. It carries the
     * reference and the code only - no cause and no stack trace: the async error handler prints whatever it is given,
     * and an SMTP server's refusal quotes the recipient's address.
     */
    static final class CustomerEmailDeferred extends RuntimeException {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        CustomerEmailDeferred(String reference, String code, int attempt) {
            super("Customer e-mail " + reference + " failed (" + code + ", attempt " + attempt + ") and will be retried",
                    null, false, false);
        }
    }
}
