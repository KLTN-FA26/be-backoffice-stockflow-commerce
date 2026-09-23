package com.stockflow.notification.internal.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Delivers one notification.
 *
 * <p>A logging stub for now — the real implementation queues to email, in-app and push channels
 * per the customer's preferences (WBS 3.18). It is a separate bean rather than inline code in the
 * listener so that swapping the transport later touches one class, and so a test can assert what
 * would have been sent without a mail server.</p>
 */
@Component
class NotificationSender {
    private final org.springframework.mail.javamail.JavaMailSender mail;
    private final com.stockflow.common.http.RestClientFactory clients;
    private final String mailFrom;
    private final java.util.Set<String> allowedHosts;

    NotificationSender(org.springframework.mail.javamail.JavaMailSender mail,
            com.stockflow.common.http.RestClientFactory clients,
            @org.springframework.beans.factory.annotation.Value("${stockflow.notification.mail-from:noreply@stockflow.local}") String mailFrom,
            @org.springframework.beans.factory.annotation.Value("${stockflow.notification.supplier-api-allowed-hosts:}") String allowedHosts) {
        this.mail = mail;
        this.clients = clients;
        this.mailFrom = mailFrom;
        this.allowedHosts = java.util.Arrays.stream(allowedHosts.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Uses the existing notification transport boundary; event retries retain the same PO payload. */
    void sendPurchaseOrder(com.stockflow.contracts.PurchaseOrderSent event) {
        if (event.lines().isEmpty()) {
            throw new IllegalArgumentException("Purchase order notification has no line snapshot; manual reconciliation required");
        }
        if ("EMAIL".equals(event.channel())) {
            var message = new org.springframework.mail.SimpleMailMessage();
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
            var uri = java.net.URI.create(event.recipient());
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getQuery() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !allowedHosts.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Supplier API host is not approved by the operator");
            }
            var response = clients.forService("supplier-po-api", event.recipient()).build().post()
                    .header("Idempotency-Key", "purchase-order:" + event.purchaseOrderId())
                    .body(event).retrieve().toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Supplier API did not acknowledge the purchase order");
            }
        } else throw new IllegalArgumentException("Unsupported supplier communication channel");
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationSender.class);

    void send(UUID recipientId, String templateCode, String subject, String body) {
        send(recipientId, null, templateCode, subject, body);
    }

    void send(UUID recipientId, String recipientEmail, String templateCode, String subject, String body) {
        log.info("NOTIFY recipient={} template={} subject='{}' body='{}'",
                recipientId == null ? "guest-email-present=" + (recipientEmail != null) : recipientId,
                templateCode, subject, body);
    }
}
