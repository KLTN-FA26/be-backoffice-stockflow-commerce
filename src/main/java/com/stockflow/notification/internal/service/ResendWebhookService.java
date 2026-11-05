package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.ProviderStatus;
import com.stockflow.notification.internal.domain.WebhookSignature;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Records what Resend reports about a message after accepting it - delivered, bounced, marked as spam, delayed, failed
 * - on the message's delivery-log row (SCRUM-352).
 *
 * <p>The endpoint is anonymous, so the signature is the authentication: an unsigned or wrongly signed call is refused
 * before the body is read for meaning, and nothing about the log is ever returned. A signed report about a message
 * this application does not know (another environment sharing the Resend account, a staff mail) or of a type it does
 * not track (sent, opened, clicked) is acknowledged and ignored: refusing it would only make Resend retry it for days.
 * Reports arrive late, twice and out of order; {@link ProviderStatus#replaces} keeps the one that says the most.</p>
 *
 * <p>A bounce or a complaint is logged at WARN with the message's reference, never the address: the customer's
 * address is wrong or unwilling, and someone should look at the customer record.</p>
 */
@Service
public class ResendWebhookService {

    /** What became of one report. */
    public enum Outcome { RECORDED, UNCHANGED, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookService.class);

    private static final Map<String, ProviderStatus> TRACKED = Map.of(
            "email.delivered", ProviderStatus.DELIVERED,
            "email.delivery_delayed", ProviderStatus.DELIVERY_DELAYED,
            "email.bounced", ProviderStatus.BOUNCED,
            "email.complained", ProviderStatus.COMPLAINED,
            "email.failed", ProviderStatus.FAILED);

    private final DeliveryLogJpaRepository logs;
    private final ObjectMapper json;
    private final Clock clock;
    private final WebhookSignature signature;

    ResendWebhookService(DeliveryLogJpaRepository logs, ObjectMapper json, Clock clock, EmailProperties properties) {
        this.logs = logs;
        this.json = json;
        this.clock = clock;
        String secret = properties.resend().webhookSecret();
        try {
            this.signature = secret == null ? null
                    : new WebhookSignature(secret, properties.resend().webhookTolerance());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("RESEND_WEBHOOK_SECRET is not a Resend signing secret (whsec_...)");
        }
    }

    /**
     * @throws BusinessException {@code WEBHOOK_UNAVAILABLE} with no secret configured (Resend retries later);
     *                           {@code WEBHOOK_SIGNATURE_INVALID} when the signature does not check out
     */
    @Transactional
    public Outcome receive(String id, String timestamp, String signatures, byte[] body) {
        if (signature == null) {
            throw new BusinessException(ErrorCode.WEBHOOK_UNAVAILABLE,
                    "RESEND_WEBHOOK_SECRET is not set: webhooks cannot be verified");
        }
        Instant now = clock.instant();
        if (!signature.verify(id, timestamp, signatures, body, now)) {
            throw new BusinessException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        JsonNode report;
        try {
            report = json.readTree(body);
        } catch (IOException unreadable) {
            log.warn("Resend webhook {}: signed but not JSON; ignored", LogSafe.text(id));
            return Outcome.IGNORED;
        }
        ProviderStatus status = TRACKED.get(report.path("type").asText(""));
        String messageId = report.path("data").path("email_id").asText("");
        if (status == null || messageId.isBlank()) {
            return Outcome.IGNORED;
        }
        Instant at = instant(report.path("created_at").asText(null), now);
        return logs.findByProviderAndProviderMessageId(EmailProvider.RESEND, messageId)
                .map(row -> {
                    if (!row.reportProviderStatus(status, at)) {
                        return Outcome.UNCHANGED;
                    }
                    if (status == ProviderStatus.BOUNCED || status == ProviderStatus.COMPLAINED
                            || status == ProviderStatus.FAILED) {
                        log.warn("E-mail {} ({}) reported {} by Resend: check the recipient's address",
                                LogSafe.text(row.getOperationReference()), LogSafe.text(row.getTemplateCode()), status);
                    } else {
                        log.debug("E-mail {} reported {} by Resend", LogSafe.text(row.getOperationReference()), status);
                    }
                    return Outcome.RECORDED;
                })
                .orElse(Outcome.IGNORED);
    }

    private static Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException unreadable) {
            try {
                return java.time.OffsetDateTime.parse(value.strip().replace(' ', 'T')).toInstant();
            } catch (DateTimeParseException stillUnreadable) {
                return fallback;
            }
        }
    }
}
