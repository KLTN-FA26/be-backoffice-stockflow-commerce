package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the sales staff of a customer's quote request on a design (kltn-docs 12 §4.8; 16 §4.2 step 5), and again when
 * the Sales Manager decides a design one is open on (12 §4.5 step 17: approved, it can be quoted now; returned, the
 * request stays open and follows the design - B40). The recipients are the holders of {@value #SALES}, system-wide:
 * the chat's routing to the responsible salesperson (16 §4.1) comes with the chat module; until then every one of them
 * hears, as the general queue would.
 *
 * <p>Delivered as every staff notice ({@link StaffNotices}): in-app first, one inbox row per person per ask or
 * decision - asking again on an open request is a new ask, with what it asks now - then e-mail best effort to the
 * people newly told, so a redelivered event tells nobody twice. System text only: SKUs and numbers, never the design's
 * name, which the customer typed.</p>
 *
 * <p>Every holder of the right hears and may read the design while the request is open (plan D7 says "the salesperson
 * with the card"): with no chat to route a request to one salesperson yet, the right is the general queue's.</p>
 */
@Component
class DesignQuoteRequestNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DesignQuoteRequestNotificationListener.class);

    static final String CATEGORY = "DESIGN_QUOTE_REQUEST";
    static final String SOURCE_TYPE = "DESIGN";
    /** Who answers a quote request (DesignQuoteRequestController). */
    static final String SALES = "design-quote-requests:READ";
    static final String LINK = "/admin/design-quote-requests";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    DesignQuoteRequestNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(DesignQuoteRequested event) {
        tell(message(event), "Quote request " + event.requestId());
    }

    @ApplicationModuleListener
    public void on(DesignReviewed event) {
        if (event.openQuoteRequestId() == null) {
            return;
        }
        tell(message(event), "Decision on design " + event.designId());
    }

    private void tell(InboxMessage message, String what) {
        List<StaffContact> recipients = staff.activeHolders(SALES, null);
        if (recipients.isEmpty()) {
            log.warn("{}: nobody active holds {}; nobody was notified", what, SALES);
            return;
        }
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_design_quote_request"));
        log.info("{}: {} salesperson(s), {} newly told", what, recipients.size(), told);
    }

    static InboxMessage message(DesignQuoteRequested event) {
        ResourceBundle texts = texts();
        boolean approved = "APPROVED".equals(event.designStatus());
        String sku = LogSafe.text(event.customerSku() != null ? event.customerSku() : event.sku());
        String body = texts.getString(approved ? "inbox.design.quote.body.approved" : "inbox.design.quote.body.pending")
                .formatted(sku, event.cartons());
        if (event.desiredDeliveryDate() != null) {
            body += " " + texts.getString("inbox.design.quote.delivery")
                    .formatted(DATE.format(event.desiredDeliveryDate()));
        }
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.design.quote.title").formatted(sku),
                body, LINK, SOURCE_TYPE, event.designId().toString(),
                "design-quote:%s:%d".formatted(event.requestId(), event.requestedAt().toEpochMilli()));
    }

    static InboxMessage message(DesignReviewed event) {
        ResourceBundle texts = texts();
        boolean approved = "APPROVED".equals(event.outcome());
        String sku = LogSafe.text(event.sku());
        String key = approved ? "inbox.design.quote.approved" : "inbox.design.quote.returned";
        return new InboxMessage(CATEGORY, approved ? InboxSeverity.INFO : InboxSeverity.WARN,
                texts.getString(key + ".title").formatted(sku), texts.getString(key + ".body").formatted(sku), LINK,
                SOURCE_TYPE, event.designId().toString(), "design-quote-decision:%s:%d".formatted(
                        event.openQuoteRequestId(), event.decidedAt().toEpochMilli()));
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
