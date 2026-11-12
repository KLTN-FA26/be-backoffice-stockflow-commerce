package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Tells the people who draw e-invoices up that a delivery has none (WF-05 E2): every delivery is invoiced (NĐ
 * 254/2026/NĐ-CP Điều 9 khoản 3), so a delivery whose draft was refused - no seller configured, nothing an invoice can
 * carry - must be in front of somebody. An in-app message, nothing by e-mail.
 *
 * <p>Who is told is decided by permission: the holders of {@value #AUDIENCE}, who can draw the draft up by hand. One
 * message per person per shipment ({@code einvoice-draft-failed:<shipment id>}), however often the delivery is told
 * again; the invoice of an order's shipping fee rest, which names no shipment, is told once per order
 * ({@code einvoice-draft-failed:fee-rest:<order id>}), with its own text. System text only - the order's number and an error code. Titles and bodies are never logged.</p>
 */
@Component
class EInvoiceDraftFailedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceDraftFailedNotificationListener.class);

    static final String CATEGORY = "EINVOICE_DRAFT_FAILED";
    static final String SOURCE_TYPE = "ORDER_SHIPMENT";
    static final String ORDER_SOURCE_TYPE = "ORDER";
    /** Who draws a draft up by hand: EInvoiceController's POST. */
    static final String AUDIENCE = "einvoices:CREATE";

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceDraftFailedNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceDraftFailed event) {
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("A delivery of order {} has no e-invoice ({}) and nobody may draw one up",
                    LogSafe.text(event.orderNumber()), LogSafe.text(event.reasonCode()));
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("A delivery of order {} has no e-invoice ({}): {} recipient(s), {} newly told",
                LogSafe.text(event.orderNumber()), LogSafe.text(event.reasonCode()), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceDraftFailed event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        if (event.shipmentId() == null) {
            return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                    texts.getString("inbox.einvoice.draft-failed.fee-rest.title").formatted(event.orderNumber()),
                    texts.getString("inbox.einvoice.draft-failed.fee-rest.body").formatted(event.orderNumber(),
                            event.reasonCode()),
                    "/invoicing/einvoices?orderId=" + event.orderId(), ORDER_SOURCE_TYPE, event.orderId().toString(),
                    "einvoice-draft-failed:fee-rest:" + event.orderId());
        }
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.einvoice.draft-failed.title").formatted(event.orderNumber()),
                texts.getString("inbox.einvoice.draft-failed.body").formatted(event.orderNumber(), event.reasonCode()),
                "/invoicing/einvoices?orderId=" + event.orderId(), SOURCE_TYPE, event.shipmentId().toString(),
                "einvoice-draft-failed:" + event.shipmentId());
    }
}
