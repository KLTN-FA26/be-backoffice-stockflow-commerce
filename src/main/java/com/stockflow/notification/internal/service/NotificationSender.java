package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.domain.SupplierEndpointPolicy;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.common.http.RestClientFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Delivers one notification.
 *
 * <p>Purchase orders use real SMTP/HTTPS transport. Other notification templates retain their
 * existing logging behavior. Transport stays separate from durable event handling.</p>
 */
@Component
class NotificationSender {
    private final JavaMailSender mail;
    private final RestClientFactory clients;
    private final String mailFrom;
    private final SupplierEndpointPolicy policy;

    NotificationSender(JavaMailSender mail,
            RestClientFactory clients,
            @Value("${stockflow.notification.mail-from:noreply@stockflow.local}") String mailFrom,
            @Value("${stockflow.notification.supplier-api-allowed-hosts:}") String allowedHosts) {
        this.mail = mail;
        this.clients = clients;
        this.mailFrom = mailFrom;
        this.policy = new SupplierEndpointPolicy(allowedHosts);
    }

    /** Uses the existing notification transport boundary; event retries retain the same PO payload. */
    void sendPurchaseOrder(PurchaseOrderSent event) {
        policy.validate(event.channel(), event.recipient());
        if (event.lines().isEmpty()) {
            throw new IllegalArgumentException("Purchase order notification has no line snapshot; manual reconciliation required");
        }
        if ("EMAIL".equals(event.channel())) {
            var message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(event.recipient());
            message.setSubject("Purchase order " + event.poNumber());
            String lines = event.lines().stream().map(line -> "%s | %s | quantity=%d | unitPrice=%s %s"
                    .formatted(line.sku(), line.description() == null ? "" : line.description(),
                            line.quantity(), line.unitPrice(), event.currency()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            message.setText("PO %s\nExpected delivery: %s\nPayment term: %d days\n%s\nTotal: %s %s\nPlease reply with confirmation or rejection and your reference."
                    .formatted(event.poNumber(), event.expectedAt(), event.paymentTermDays(), lines,
                            event.totalAmount(), event.currency()));
            mail.send(message);
        } else if ("API".equals(event.channel())) {
            var response = clients.forService("supplier-po-api", event.recipient()).build().post()
                    .header("Idempotency-Key", "purchase-order:" + event.purchaseOrderId())
                    .body(new SupplierPurchaseOrderMessage(event.purchaseOrderId(), event.poNumber(), event.supplierId(),
                            event.channel(), event.recipient(), event.totalAmount(), event.currency(), event.expectedAt(),
                            event.paymentTermDays(), event.lines())).retrieve().toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Supplier API did not acknowledge the purchase order");
            }
        } else throw new IllegalArgumentException("Unsupported supplier communication channel");
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    /** Internal recovery generations must not change the supplier's idempotent PO payload. */
    private record SupplierPurchaseOrderMessage(UUID purchaseOrderId, String poNumber, UUID supplierId,
            String channel, String recipient, BigDecimal totalAmount, String currency,
            LocalDate expectedAt, int paymentTermDays, List<PurchaseOrderSent.Line> lines) {}

    void send(UUID recipientId, String templateCode, String subject, String body) {
        send(recipientId, null, templateCode, subject, body);
    }

    void send(UUID recipientId, String recipientEmail, String templateCode, String subject, String body) {
        log.info("NOTIFY recipient={} template={} subject='{}' body='{}'",
                recipientId == null ? "guest-email-present=" + (recipientEmail != null) : recipientId,
                templateCode, subject, body);
    }
}
