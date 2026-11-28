package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells Sales that a customer's step on a quote made the next one theirs (accepted, changes asked), after
 * the customer's transaction committed: in-app first, then e-mail best effort ({@link StaffNotices}), one inbox row per
 * person per step ({@code quote:<quote>:<action>:<occurredAt>}). A mail server that is down delays nothing and is never
 * a reason to replay the event.
 *
 * <p>The notice goes to whoever looks after the quote's <b>customer</b> today (kltn-docs 00 §6 as changed on
 * 2026-11-28, 14 §4.4 step 20; {@link SalesNotices}): its owner, or the owner's cover - not the account that sent the
 * quote, so a customer moved to another salesperson is followed by the new one. With no active owner it goes to the
 * Sales Managers, marked "cần phân công" - never to every Sales account, as it did until then (user decision N1 of
 * 2026-10-13, replaced). This only tells them; who may read or answer the quote is unchanged. With nobody to tell it
 * is logged at WARN, never dropped silently. The in-app text names the quote number only - not the customer, not
 * what they typed.</p>
 */
@Component
class QuoteNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationListener.class);

    private final NotificationSender sender;
    private final SalesNotices sales;
    private final StaffNotices notices;

    QuoteNotificationListener(NotificationSender sender, SalesNotices sales, StaffNotices notices) {
        this.sender = sender;
        this.sales = sales;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(CustomerActedOnQuote event) {
        // Asked now, not carried in the stored event: the owner of today decides (no e-mail in event_publication).
        SalesAudience audience = sales.audiences().ofCustomer(event.customerId()).orElse(null);
        List<StaffContact> recipients = audience == null ? List.of() : sales.contacts(audience);
        if (recipients.isEmpty()) {
            log.warn("Quote {}: customer {} {}; nobody looks after the customer and no manager is active - nobody "
                    + "was notified", LogSafe.text(event.quoteNumber()), event.customerId(), event.action());
            return;
        }
        boolean needsAssignment = audience.needsAssignment();
        int told = notices.deliver(recipients, message(event, needsAssignment),
                emails -> sender.sendQuoteNotice(event, emails, needsAssignment));
        log.info("Quote {}: {} notice to {} account(s){}, {} newly told", LogSafe.text(event.quoteNumber()),
                event.action(), recipients.size(), needsAssignment ? " (the customer needs an owner)" : "", told);
    }

    static InboxMessage message(CustomerActedOnQuote event, boolean needsAssignment) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String action = event.action().name().toLowerCase(Locale.ROOT);
        String title = messages.getString("inbox.quote." + action + ".title").formatted(event.quoteNumber());
        return new InboxMessage("QUOTE", InboxSeverity.INFO, title,
                needsAssignment ? messages.getString("inbox.sales.needs-assignment") : null,
                "/admin/sales/quotes/" + event.quoteId(), "QUOTE", event.quoteId().toString(),
                "quote:%s:%s:%s".formatted(event.quoteId(), event.action(), event.occurredAt()));
    }
}
