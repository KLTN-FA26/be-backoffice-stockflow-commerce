package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomerActedOnQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells Sales that a customer's step on a quote made the next one theirs (accepted, changes asked,
 * reorder). E-mail through the configured SMTP server, after the customer's transaction committed: a
 * mail server that is down delays the notice, never the customer's answer, and Modulith keeps the event
 * until this method returns normally.
 *
 * <p>An event without a recipient is logged at WARN and completed: retrying cannot produce an address, and
 * mailing someone else would be inventing who owns the customer.</p>
 */
@Component
class QuoteNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationListener.class);

    private final NotificationSender sender;

    QuoteNotificationListener(NotificationSender sender) {
        this.sender = sender;
    }

    @ApplicationModuleListener
    public void on(CustomerActedOnQuote event) {
        if (event.salesEmail() == null || event.salesEmail().isBlank()) {
            log.warn("Quote {}: customer {} {} but no active Sales account with an e-mail handled it; nobody was "
                    + "notified", event.quoteNumber(), event.customerId(), event.action());
            return;
        }
        sender.sendQuoteNotice(event);
        log.info("Quote {}: {} notice e-mailed to Sales user {}", event.quoteNumber(), event.action(),
                event.salesUserId());
    }
}
