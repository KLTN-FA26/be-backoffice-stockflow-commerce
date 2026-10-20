package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.CustomerActedOnQuote;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells Sales that a customer's step on a quote made the next one theirs (accepted, changes asked, reorder), after
 * the customer's transaction committed: in-app first, then e-mail best effort ({@link StaffNotices}), one inbox row per
 * person per step ({@code quote:<quote>:<action>:<occurredAt>}). A mail server that is down delays nothing and is never
 * a reason to replay the event.
 *
 * <p>The notice goes to the Sales account that handled the quote. When that account is gone, locked or has no
 * e-mail ({@code salesEmail} null), it goes to every active Sales account, marked as a hand-over, so that one of
 * them takes the customer on (user decision N1, 2026-10-13) - each person once. This only tells them; who may
 * read or answer the quote is unchanged. With no active Sales account at all it is logged at WARN, never
 * dropped silently. The in-app text names the quote and order numbers only - not the customer, not what they
 * typed.</p>
 */
@Component
class QuoteNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(QuoteNotificationListener.class);

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    QuoteNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(CustomerActedOnQuote event) {
        // The responsible account is looked up now, not carried in the stored event (no e-mail in event_publication).
        java.util.Optional<StaffContact> handler = staff.activeContact(event.salesUserId());
        boolean responsible = handler.isPresent();
        List<StaffContact> recipients = responsible ? List.of(handler.get()) : staff.activeStaff(Role.SALES_STAFF);
        if (recipients.isEmpty()) {
            log.warn("Quote {}: customer {} {}; the responsible Sales account {} is not active and there is no active "
                    + "Sales account - nobody was notified", LogSafe.text(event.quoteNumber()), event.customerId(),
                    event.action(), event.salesUserId());
            return;
        }
        int told = notices.deliver(recipients, message(event, !responsible),
                emails -> sender.sendQuoteNotice(event, emails, !responsible));
        log.info("Quote {}: {} notice to {} Sales account(s){}, {} newly told", LogSafe.text(event.quoteNumber()),
                event.action(), recipients.size(), responsible ? "" : " to take over from " + event.salesUserId(), told);
    }

    static InboxMessage message(CustomerActedOnQuote event, boolean takeover) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String action = event.action().name().toLowerCase(Locale.ROOT);
        String title = messages.getString("inbox.quote." + action + ".title").formatted(event.quoteNumber(),
                event.sourceOrderNumber() == null ? "" : event.sourceOrderNumber());
        return new InboxMessage("QUOTE", InboxSeverity.INFO, title,
                takeover ? messages.getString("inbox.quote.takeover") : null,
                "/admin/sales/quotes/" + event.quoteId(), "QUOTE", event.quoteId().toString(),
                "quote:%s:%s:%s".formatted(event.quoteId(), event.action(), event.occurredAt()));
    }
}
