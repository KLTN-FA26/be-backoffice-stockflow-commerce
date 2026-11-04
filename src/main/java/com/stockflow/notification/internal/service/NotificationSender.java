package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.api.PortalInvitationMail;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailTransport;
import com.stockflow.notification.internal.domain.SupplierEndpointPolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Words and addresses the staff, supplier and invitation mail, and hands it to the {@link EmailTransport} - which
 * provider that is (Resend, SMTP, the test recorder) is configuration, not this class's concern. The supplier API
 * channel of a purchase order is the one delivery here that is not e-mail.
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
    private final RestClientFactory clients;
    private final String mailFrom;
    private final SupplierEndpointPolicy policy;

    NotificationSender(
            EmailTransport transport,
            RestClientFactory clients,
            @Value("${stockflow.notification.email.from:noreply@stockflow.local}") String mailFrom,
            @Value("${stockflow.notification.supplier-api-allowed-hosts:}") String allowedHosts) {
        this.transport = transport;
        this.clients = clients;
        // The same reading as EmailProperties.from: a variable set to "" means unconfigured.
        this.mailFrom = mailFrom == null || mailFrom.isBlank() ? EmailProperties.UNCONFIGURED_FROM : mailFrom.strip();
        this.policy = new SupplierEndpointPolicy(allowedHosts);
    }

    /**
     * The portal invitation, in Vietnamese like the purchase-order mail. Never logs the link, and sends no idempotency
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
     * Tells the staff who can unblock a held production order (Jira 396 / 398 / 401), in Vietnamese like the
     * other staff mail. One message to every recipient: a refusal is retried as a whole from the event registry.
     */
    void sendProductionHold(ProductionOrderHeld event, com.stockflow.common.security.Role audience,
                            List<String> recipients) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String reason = messages.containsKey("production.hold.reason." + event.reasonCode())
                ? messages.getString("production.hold.reason." + event.reasonCode()) : event.reasonCode();
        String source = event.sampleRequestId() != null
                ? messages.getString("production.hold.source.sample").formatted(event.sampleRequestId())
                : messages.getString("production.hold.source.order").formatted(event.salesOrderId());
        // Facts only: the note the workshop typed stays on the production order, which the reader opens.
        String note = event.blanksNeeded() == null || event.blankSku() == null ? ""
                : messages.getString("production.hold.blanks").formatted(event.blanksNeeded(), event.blankSku());
        sendToStaff(recipients,
                messages.getString("production.hold.mail.subject").formatted(event.orderNumber(), reason),
                messages.getString("production.hold.mail.body." + audience.name().toLowerCase(Locale.ROOT))
                        .formatted(event.orderNumber(), reason, source, note),
                "staff_production_hold");
    }

    /**
     * Uses the existing notification transport boundary; event retries retain the same PO payload. By e-mail the
     * idempotency key is the delivery's operation reference and generation: a retry of one generation is the same
     * message, a recovery generation is a deliberate new one.
     */
    void sendPurchaseOrder(PurchaseOrderSent event) {
        policy.validate(event.channel(), event.recipient());
        if (!event.cancellation() && event.lines().isEmpty()) {
            throw new IllegalArgumentException(
                    "Purchase order notification has no line snapshot; manual reconciliation"
                        + " required");
        }
        if ("EMAIL".equals(event.channel())) {
            var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
            String subject =
                    event.amendment()
                            ? messages.getString("po.mail.amend.subject")
                                    .formatted(event.poNumber(), event.amendmentRevisionNo())
                            : messages.getString(
                                            event.cancellation()
                                                    ? "po.mail.cancel.subject"
                                                    : "po.mail.subject")
                                    .formatted(event.poNumber());
            String lines =
                    event.lines().stream()
                            .map(
                                    line ->
                                            messages.getString("po.mail.line")
                                                    .formatted(
                                                            line.sku(),
                                                            line.description() == null
                                                                    ? ""
                                                                    : line.description(),
                                                            line.quantity(),
                                                            line.unitPrice(),
                                                            event.currency()))
                            .collect(Collectors.joining("\n"));
            var buyer = event.buyer();
            String identity =
                    buyer == null
                            ? messages.getString("po.mail.legacy")
                            : messages.getString("po.mail.buyer")
                                    .formatted(
                                            buyer.companyName(),
                                            buyer.companyAddress(),
                                            buyer.contactName(),
                                            buyer.phone(),
                                            buyer.email(),
                                            buyer.receivingAddress());
            String text =
                    event.amendment()
                            ? messages.getString("po.mail.amend.body")
                                    .formatted(
                                            event.poNumber(),
                                            identity,
                                            event.amendmentRevisionNo(),
                                            event.amendmentReason(),
                                            changes(event, messages),
                                            event.expectedAt(),
                                            event.paymentTermDays(),
                                            lines,
                                            event.totalAmount(),
                                            event.currency())
                    : event.cancellation()
                            ? messages.getString("po.mail.cancel.body")
                                    .formatted(
                                            event.poNumber(), identity, event.cancellationReason())
                            : messages.getString("po.mail.body")
                                    .formatted(
                                            event.poNumber(),
                                            identity,
                                            event.expectedAt(),
                                            event.paymentTermDays(),
                                            lines,
                                            event.totalAmount(),
                                            event.currency());
            transport.send(new Email(mailFrom, List.of(event.recipient()), buyer == null ? null : buyer.email(),
                    subject, text, null,
                    event.operationReference() + ":g" + event.deliveryGeneration(),
                    event.amendment() ? "purchase_order_amendment"
                            : event.cancellation() ? "purchase_order_cancellation" : "purchase_order"));
        } else if ("API".equals(event.channel())) {
            var response =
                    clients.forService("supplier-po-api", event.recipient())
                            .build()
                            .post()
                            .header("Idempotency-Key", event.operationReference())
                            .body(
                                    new SupplierPurchaseOrderMessage(
                                            event.purchaseOrderId(),
                                            event.poNumber(),
                                            event.supplierId(),
                                            event.channel(),
                                            event.recipient(),
                                            event.totalAmount(),
                                            event.currency(),
                                            event.expectedAt(),
                                            event.paymentTermDays(),
                                            event.lines(),
                                            event.buyer(),
                                            messageType(event),
                                            event.cancellationReason(),
                                            event.amendmentRevisionNo(),
                                            event.amendmentReason(),
                                            event.changes()))
                            .retrieve()
                            .toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RestClientResponseException(
                        "Supplier API did not acknowledge the purchase order",
                        response.getStatusCode(),
                        "",
                        response.getHeaders(),
                        null,
                        null);
            }
        } else throw new IllegalArgumentException("Unsupported supplier communication channel");
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

    private static String messageType(PurchaseOrderSent event) {
        if (event.amendment()) return "AMENDMENT";
        return event.cancellation() ? "CANCELLATION" : "PURCHASE_ORDER";
    }

    /**
     * What changed against the revision the supplier had, one line per change, in the supplier's words: a header
     * change (no line) under its field's name, a line change with its line and SKU, a removed or added line as such.
     * The event carries field codes, which the API channel passes on untouched; they become labels here only, and a
     * code this deployment has no label for is written as it is.
     */
    private static String changes(PurchaseOrderSent event, ResourceBundle messages) {
        return event.changes().stream()
                .map(change -> change(change, messages))
                .collect(Collectors.joining("\n"));
    }

    private static String change(PurchaseOrderSent.Change change, ResourceBundle messages) {
        String key = "po.mail.field." + change.field();
        String label = messages.containsKey(key) ? messages.getString(key) : change.field();
        if (change.lineNo() == null) {
            return messages.getString("po.mail.change.header")
                    .formatted(label, value(change.before(), messages), value(change.after(), messages));
        }
        return switch (change.field()) {
            case "removed" -> messages.getString("po.mail.change.line")
                    .formatted(change.lineNo(), change.sku(), label, value(change.before(), messages));
            case "added" -> messages.getString("po.mail.change.line")
                    .formatted(change.lineNo(), change.sku(), label, value(change.after(), messages));
            default -> messages.getString("po.mail.change")
                    .formatted(change.lineNo(), change.sku(), label, value(change.before(), messages),
                            value(change.after(), messages));
        };
    }

    /** A side that has no value - a note added or cleared - reads as such, not as a blank the reader may miss. */
    private static String value(String value, ResourceBundle messages) {
        return value == null || value.isBlank() ? messages.getString("po.mail.change.empty") : value;
    }

    /**
     * Internal recovery generations must not change the supplier's idempotent PO payload. The amendment fields are
     * omitted when empty (only those: the existing nulls stay as they were), so an order or a cancellation is posted as
     * before SCRUM-117.
     */
    private record SupplierPurchaseOrderMessage(
            UUID purchaseOrderId,
            String poNumber,
            UUID supplierId,
            String channel,
            String recipient,
            BigDecimal totalAmount,
            String currency,
            LocalDate expectedAt,
            int paymentTermDays,
            List<PurchaseOrderSent.Line> lines,
            PurchaseOrderSent.Buyer buyer,
            String type,
            String cancellationReason,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Integer revisionNo,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) String amendmentReason,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<PurchaseOrderSent.Change> changes) {}
}
