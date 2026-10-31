package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.api.PortalInvitationMail;
import com.stockflow.notification.internal.domain.SupplierEndpointPolicy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
 * Delivers one notification.
 *
 * <p>Purchase orders use real SMTP/HTTPS transport. Other notification templates retain their
 * existing logging behavior. Transport stays separate from durable event handling.
 */
@Component
class NotificationSender {
    /** The business runs in Vietnam; the customer reads the deadline in its local time. */
    private static final DateTimeFormatter INVITATION_EXPIRY =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final JavaMailSender mail;
    private final RestClientFactory clients;
    private final String mailFrom;
    private final SupplierEndpointPolicy policy;

    NotificationSender(
            JavaMailSender mail,
            RestClientFactory clients,
            @Value("${stockflow.notification.mail-from:noreply@stockflow.local}") String mailFrom,
            @Value("${stockflow.notification.supplier-api-allowed-hosts:}") String allowedHosts) {
        this.mail = mail;
        this.clients = clients;
        this.mailFrom = mailFrom;
        this.policy = new SupplierEndpointPolicy(allowedHosts);
    }

    /** The portal invitation, in Vietnamese like the purchase-order mail. Never logs the link. */
    void sendPortalInvitation(PortalInvitationMail invitation) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        var message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(invitation.recipient());
        message.setSubject(messages.getString("portal.invitation.mail.subject")
                .formatted(invitation.companyName()));
        message.setText(messages.getString("portal.invitation.mail.body").formatted(
                invitation.contactName() == null ? "" : invitation.contactName(),
                invitation.companyName(),
                invitation.activationUrl(),
                INVITATION_EXPIRY.format(invitation.expiresAt())));
        mail.send(message);
    }

    /**
     * Tells the Sales user that a customer's step on a quote is theirs to follow up (accepted, changes asked,
     * reorder). Internal staff mail; the customer's own data stays in the back office, only the quote and order
     * numbers are written (the event carries no name, review of #109 item 10): the quote says who. {@code takeover}: the responsible account is no longer
     * active and this goes to the active Sales team to take the customer on (N1).
     */
    void sendQuoteNotice(CustomerActedOnQuote event, List<String> recipients, boolean takeover) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = "quote.mail." + event.action().name().toLowerCase(Locale.ROOT);
        String detail = event.sourceOrderNumber() == null ? "" : event.sourceOrderNumber();
        String subject = messages.getString(key + ".subject").formatted(event.quoteNumber(), detail);
        String body = messages.getString(key + ".body").formatted(event.quoteNumber(), detail);
        var message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipients.toArray(String[]::new));
        message.setSubject(takeover ? messages.getString("quote.mail.takeover.subject").formatted(subject) : subject);
        message.setText(takeover ? messages.getString("quote.mail.takeover.body").formatted(body) : body);
        mail.send(message);
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
        var message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipients.toArray(String[]::new));
        message.setSubject(messages.getString("production.hold.mail.subject").formatted(event.orderNumber(), reason));
        message.setText(messages.getString("production.hold.mail.body." + audience.name().toLowerCase(Locale.ROOT))
                .formatted(event.orderNumber(), reason, source, note));
        mail.send(message);
    }

    /**
     * Uses the existing notification transport boundary; event retries retain the same PO payload.
     */
    void sendPurchaseOrder(PurchaseOrderSent event) {
        policy.validate(event.channel(), event.recipient());
        if (!event.cancellation() && event.lines().isEmpty()) {
            throw new IllegalArgumentException(
                    "Purchase order notification has no line snapshot; manual reconciliation"
                        + " required");
        }
        if ("EMAIL".equals(event.channel())) {
            var message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(event.recipient());
            var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
            message.setSubject(
                    event.amendment()
                            ? messages.getString("po.mail.amend.subject")
                                    .formatted(event.poNumber(), event.amendmentRevisionNo())
                            : messages.getString(
                                            event.cancellation()
                                                    ? "po.mail.cancel.subject"
                                                    : "po.mail.subject")
                                    .formatted(event.poNumber()));
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
            if (buyer != null) message.setReplyTo(buyer.email());
            message.setText(
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
                                            event.currency()));
            mail.send(message);
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

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    /**
     * Sends one plain e-mail with a system-generated subject and body (the inventory alert digest). Throws when the
     * mail server refuses; the caller decides whether that matters. Neither the subject nor the body is logged.
     */
    void sendInventoryAlertEmail(String toEmail, String subject, String body) {
        var message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mail.send(message);
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

    void send(UUID recipientId, String templateCode, String subject, String body) {
        send(recipientId, null, templateCode, subject, body);
    }

    void send(
            UUID recipientId,
            String recipientEmail,
            String templateCode,
            String subject,
            String body) {
        // Subject and body can carry what a customer or an operator typed (names, notes, a payment
        // provider's failure text), so they are never logged: the recipient id or the fact that a
        // guest e-mail is present, the template and the sizes are enough to trace a delivery.
        log.info(
                "NOTIFY recipient={} template={} subjectLength={} bodyLength={}",
                recipientId == null
                        ? "guest-email-present=" + (recipientEmail != null)
                        : recipientId,
                LogSafe.text(templateCode),
                subject == null ? 0 : subject.length(),
                body == null ? 0 : body.length());
    }
}
