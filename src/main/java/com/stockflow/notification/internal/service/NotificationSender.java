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
