package com.stockflow.notification.internal.service;

import com.stockflow.common.security.Role;
import com.stockflow.contracts.CustomerActedOnQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tells Sales that a customer's step on a quote made the next one theirs (accepted, changes asked,
 * reorder). E-mail through the configured SMTP server, after the customer's transaction committed: a
 * mail server that is down delays the notice, never the customer's answer, and Modulith keeps the event
 * until this method returns normally.
 *
 * <p>The notice goes to the Sales account that handled the quote. When that account is gone, locked or has no
 * e-mail ({@code salesEmail} null), it goes to every active Sales account, marked as a hand-over, so that one of
 * them takes the customer on (user decision N1, 2026-10-13) - each address once. This only tells them; who may
 * read or answer the quote is unchanged. With no active Sales account at all it is logged at WARN, never
 * dropped silently.</p>
 */
@Component
class QuoteNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationListener.class);

    private final NotificationSender sender;
    private final StaffDirectory staff;

    QuoteNotificationListener(NotificationSender sender, StaffDirectory staff) {
        this.sender = sender;
        this.staff = staff;
    }

    @ApplicationModuleListener
    public void on(CustomerActedOnQuote event) {
        if (event.salesEmail() != null && !event.salesEmail().isBlank()) {
            sender.sendQuoteNotice(event, List.of(event.salesEmail().strip()), false);
            log.info("Quote {}: {} notice e-mailed to Sales user {}", event.quoteNumber(), event.action(),
                    event.salesUserId());
            return;
        }
        List<String> team = staff.activeEmails(Role.SALES_STAFF);
        if (team.isEmpty()) {
            log.warn("Quote {}: customer {} {}; the responsible Sales account {} is not active and no active Sales "
                    + "account has an e-mail - nobody was notified", event.quoteNumber(), event.customerId(),
                    event.action(), event.salesUserId());
            return;
        }
        sender.sendQuoteNotice(event, team, true);
        log.info("Quote {}: {} notice e-mailed to {} active Sales account(s) to take over from {}", event.quoteNumber(),
                event.action(), team.size(), event.salesUserId());
    }
}
