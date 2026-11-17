package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.EmailProvider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Locale;

/**
 * How e-mail leaves the application ({@code stockflow.notification.email}; see {@code .env.example}).
 *
 * @param provider   which transport: {@code resend} on the servers, {@code smtp} on a laptop (Mailpit),
 *                   {@code recording} in the tests
 * @param from       the sender of every message, {@code "StockFlow <no-reply@domain>"} or a bare address. With Resend
 *                   its domain must be verified in the Resend dashboard, or every send is refused (403)
 * @param replyTo    where a customer's reply goes (Sales' mailbox); blank means the customer messages say not to reply
 * @param locale     the language of customer and staff messages; the customers are Vietnamese
 * @param senderName how the customer messages sign off
 * @param customer   the customer messages (SCRUM-352)
 * @param resend     Resend's API and webhook
 */
@ConfigurationProperties(prefix = "stockflow.notification.email")
public record EmailProperties(
        @DefaultValue("smtp") EmailProvider provider,
        String from,
        String replyTo,
        @DefaultValue("vi") Locale locale,
        @DefaultValue("StockFlow") String senderName,
        @DefaultValue Customer customer,
        @DefaultValue Resend resend) {

    /** The sender when none is configured; a {@code .local} address, which the Resend transport refuses at startup. */
    static final String UNCONFIGURED_FROM = "noreply@stockflow.local";

    public EmailProperties {
        // An environment variable set to "" is a value, not an absence: ${RESEND_FROM:...} does not fall back on it.
        // Blank means unconfigured, which is fine for Mailpit and stops a Resend server at startup.
        from = from == null || from.isBlank() ? UNCONFIGURED_FROM : from.strip();
        replyTo = replyTo == null || replyTo.isBlank() ? null : replyTo.strip();
    }

    /**
     * @param enabled          switches the customer messages off without touching staff or supplier mail. Off unless
     *                         configured: customer e-mail is a later phase of the business documents, which keep
     *                         only the in-app inbox for now. A message not sent for this reason is recorded as
     *                         skipped, and not sent later when it is switched back on. Does not govern e-invoice
     *                         mail ({@code einvoiceEnabled})
     * @param maxAttempts      attempts of one message before it is given up (recorded as a terminal failure)
     * @param firstRetryDelay  the wait after the first failure worth retrying; doubled after each further one
     * @param maxRetryDelay    the longest wait between two attempts, whatever the provider's Retry-After says
     * @param einvoiceEnabled  switches the e-invoice messages to the buyer (an invoice issued, an error notice) on or
     *                         off, apart from the order mail. <b>On unless configured</b>: an issued invoice is a
     *                         legal record that the seller sends the buyer (NĐ 254/2026/NĐ-CP Điều 12 khoản 3, the
     *                         way agreed - here the e-mail the invoice names, and the portal), not a later-phase
     *                         courtesy like the order mail. Off is for a server that must never write to real
     *                         buyers; a message not sent for this reason is recorded as skipped
     *                         ({@code EINVOICE_EMAIL_DISABLED})
     */
    public record Customer(@DefaultValue("false") boolean enabled,
                           @DefaultValue("10") int maxAttempts,
                           @DefaultValue("PT1M") Duration firstRetryDelay,
                           @DefaultValue("PT6H") Duration maxRetryDelay,
                           @DefaultValue("true") boolean einvoiceEnabled) {

        /** The templates of e-invoice messages ({@code einvoice.*}): governed by {@code einvoiceEnabled}. */
        static final String EINVOICE_TEMPLATES = "einvoice.";

        public Customer {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("stockflow.notification.email.customer.max-attempts must be >= 1");
            }
            if (firstRetryDelay.isNegative() || firstRetryDelay.isZero()
                    || maxRetryDelay.compareTo(firstRetryDelay) < 0) {
                throw new IllegalArgumentException("Customer e-mail retry delays must be positive, first <= max");
            }
        }

        /** Null when a message of this template may be sent; otherwise the reason it is skipped. */
        String disabledReason(String templateCode) {
            if (templateCode != null && templateCode.startsWith(EINVOICE_TEMPLATES)) {
                return einvoiceEnabled ? null : "EINVOICE_EMAIL_DISABLED";
            }
            return enabled ? null : "CUSTOMER_EMAIL_DISABLED";
        }
    }

    /**
     * @param apiKey                Resend API key ({@code RESEND_API_KEY}); required when the provider is resend
     * @param baseUrl               Resend's API; overridden only by tests pointing at a local stub
     * @param webhookSecret         the signing secret of the Resend webhook ({@code whsec_...}); blank refuses every
     *                              webhook call
     * @param webhookTolerance      how far a webhook's timestamp may be from this server's clock (replay window)
     * @param requestsPerSecond     sends this instance makes per second at most; Resend allows a team 2 by default
     * @param maxConcurrentRequests sends in flight at once from this instance
     */
    public record Resend(String apiKey,
                         @DefaultValue("https://api.resend.com") String baseUrl,
                         String webhookSecret,
                         @DefaultValue("PT5M") Duration webhookTolerance,
                         @DefaultValue("2") int requestsPerSecond,
                         @DefaultValue("2") int maxConcurrentRequests) {

        public Resend {
            apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
            webhookSecret = webhookSecret == null || webhookSecret.isBlank() ? null : webhookSecret.strip();
            if (requestsPerSecond < 1 || maxConcurrentRequests < 1) {
                throw new IllegalArgumentException("Resend request limits must be >= 1");
            }
        }

        /** Never prints the key or the secret: this record is part of a startup failure message when binding fails. */
        @Override
        public String toString() {
            return "Resend[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "unset" : "set")
                    + ", webhookSecret=" + (webhookSecret == null ? "unset" : "set") + "]";
        }
    }
}
