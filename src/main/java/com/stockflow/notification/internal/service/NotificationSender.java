package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.notification.api.PortalInvitationMail;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailTransport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Words and addresses the staff and invitation mail, and hands it to the {@link EmailTransport} - which provider that
 * is (Resend, SMTP, the test recorder) is configuration, not this class's concern. The purchase order mail and its
 * supplier API channel went with purchasing (4A-8); the producer's messages of a production order are
 * {@link ProductionOrderMessages}'.
 *
 * <p>Customer messages about an order are not here: they are rendered from templates and recorded per message by
 * {@link CustomerEmailDelivery}. What stays here is plain text, as it always was.</p>
 */
@Component
class NotificationSender {
    /** The business runs in Vietnam; the customer reads the deadline in its local time. */
    private static final DateTimeFormatter INVITATION_EXPIRY =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    /** Resend takes at most 50 recipients a message; a staff list is split rather than refused. */
    private static final int RECIPIENTS_PER_MESSAGE = ResendEmailTransport.MAX_RECIPIENTS;

    private final EmailTransport transport;
    private final String mailFrom;

    NotificationSender(
            EmailTransport transport,
            @Value("${stockflow.notification.email.from:noreply@stockflow.local}") String mailFrom) {
        this.transport = transport;
        // The same reading as EmailProperties.from: a variable set to "" means unconfigured.
        this.mailFrom = mailFrom == null || mailFrom.isBlank() ? EmailProperties.UNCONFIGURED_FROM : mailFrom.strip();
    }

    /**
     * The portal invitation, in Vietnamese like all staff and partner mail. Never logs the link, and sends no idempotency
     * key: a resend is a new invitation with a new secret, never a replay of the old one.
     */
    void sendPortalInvitation(PortalInvitationMail invitation) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        transport.send(new Email(mailFrom, List.of(invitation.recipient()), null,
                messages.getString("portal.invitation.mail.subject").formatted(invitation.companyName()),
                messages.getString("portal.invitation.mail.body").formatted(
                        invitation.contactName() == null ? "" : invitation.contactName(),
                        invitation.companyName(),
                        invitation.activationUrl(),
                        INVITATION_EXPIRY.format(invitation.expiresAt())),
                null, null, "portal_invitation"));
    }

    /**
     * Tells the Sales user that a customer's step on a quote is theirs to follow up (accepted, changes asked,
     * reorder). Internal staff mail; the customer's own data stays in the back office, only the quote and order
     * numbers are written (the event carries no name, review of #109 item 10): the quote says who. {@code takeover}:
     * the responsible account is no longer active and this goes to the active Sales team to take the customer on (N1).
     */
    void sendQuoteNotice(CustomerActedOnQuote event, List<String> recipients, boolean takeover) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = "quote.mail." + event.action().name().toLowerCase(Locale.ROOT);
        String detail = event.sourceOrderNumber() == null ? "" : event.sourceOrderNumber();
        String subject = messages.getString(key + ".subject").formatted(event.quoteNumber(), detail);
        String body = messages.getString(key + ".body").formatted(event.quoteNumber(), detail);
        sendToStaff(recipients,
                takeover ? messages.getString("quote.mail.takeover.subject").formatted(subject) : subject,
                takeover ? messages.getString("quote.mail.takeover.body").formatted(body) : body,
                "staff_quote");
    }

    /**
     * Sends one plain e-mail with a system-generated subject and body (the inventory alert digest). Throws when the
     * transport refuses; the caller decides whether that matters. Neither the subject nor the body is logged.
     */
    void sendInventoryAlertEmail(String toEmail, String subject, String body) {
        transport.send(new Email(mailFrom, List.of(toEmail), null, subject, body, null, null,
                "staff_inventory_alert"));
    }

    /**
     * A staff notice by e-mail, worded as its in-app message (title as the subject; the body, then the page to open).
     * System text only, as the in-app message is.
     */
    void sendStaffNotice(List<String> recipients, com.stockflow.notification.api.InboxMessage message,
                         String category) {
        String body = message.body() == null ? "" : message.body();
        sendToStaff(recipients, message.title(), message.link() == null ? body : body + "\n\n" + message.link(),
                category);
    }

    /**
     * One staff message to many people, in messages of at most {@value #RECIPIENTS_PER_MESSAGE} recipients each.
     * A refused part throws: the callers deliver staff mail best effort and log it.
     */
    private void sendToStaff(List<String> recipients, String subject, String body, String category) {
        for (int from = 0; from < recipients.size(); from += RECIPIENTS_PER_MESSAGE) {
            List<String> part = recipients.subList(from, Math.min(recipients.size(), from + RECIPIENTS_PER_MESSAGE));
            transport.send(new Email(mailFrom, part, null, subject, body, null, null, category));
        }
    }
}
