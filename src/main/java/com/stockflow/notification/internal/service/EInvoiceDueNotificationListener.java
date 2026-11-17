package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceIssueDue;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Tells the people who issue e-invoices that one is close to its deadline (WF-05 E2): an invoice is signed and sent for
 * the tax authority's code no later than the working day after its date (NĐ 254/2026/NĐ-CP Phụ lục điểm 7), and a draft
 * nobody opened is running out of that time. An in-app message, nothing by e-mail.
 *
 * <p>Who is told is decided by permission, not by role name: the holders of {@value #AUDIENCE} - whoever may issue.
 * One message per person per invoice ({@code einvoice-due:<id>}): the event is published once per invoice, and a
 * redelivery tells nobody twice. System text only - the invoice's and the order's codes, and dates. Titles and bodies
 * are never logged.</p>
 */
@Component
class EInvoiceDueNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceDueNotificationListener.class);

    static final String CATEGORY = "EINVOICE_DUE";
    static final String SOURCE_TYPE = "EINVOICE";
    /** Who issues: EInvoiceController's issuance. */
    static final String AUDIENCE = "einvoices:APPROVE";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceDueNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceIssueDue event) {
        // Invoices are the business's, not a warehouse's: the business-wide holders (null: scope ALL).
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("E-invoice {} is close to its deadline and nobody may issue it", LogSafe.text(event.code()));
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("E-invoice {} close to its deadline: {} recipient(s), {} newly told", LogSafe.text(event.code()),
                recipients.size(), told);
    }

    static InboxMessage message(EInvoiceIssueDue event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String due = MOMENT.format(event.issueDueAt().atZone(BusinessCalendar.ZONE));
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.einvoice.due.title").formatted(event.code(), due),
                texts.getString("inbox.einvoice.due.body").formatted(event.code(), event.orderNumber(),
                        DAY.format(event.issueDate()), due),
                "/invoicing/einvoices/" + event.einvoiceId(), SOURCE_TYPE, event.einvoiceId().toString(),
                "einvoice-due:" + event.einvoiceId());
    }
}
