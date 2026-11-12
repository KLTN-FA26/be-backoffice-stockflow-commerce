package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceNeedsAttention;
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
 * Tells the people who issue e-invoices that one was refused, has waited too long for the provider, or is no longer known
 * to the provider and will not be sent again unless they send it (WF-05 E5 and E3; plan
 * §1.4 "Bị từ chối hoặc lỗi thì báo kế toán"). Without it a refusal is seen only by whoever happens to open the list,
 * while the invoice's deadline - the working day after its date - keeps running. An in-app message, as the deadline
 * warning is ({@link EInvoiceDueNotificationListener}); nothing by e-mail.
 *
 * <p>Who is told is decided by permission: the holders of {@link EInvoiceDueNotificationListener#AUDIENCE}. One message
 * per person, invoice, cause and submission ({@code einvoice-<cause>:<id>:<submittedAt>}): an invoice refused,
 * corrected, sent again and refused again is a new refusal and is announced again; a redelivered event tells nobody
 * twice. System text only - codes and dates; the provider's sentence about a refusal is read on the invoice.</p>
 */
@Component
class EInvoiceAttentionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceAttentionNotificationListener.class);

    static final String CATEGORY = "EINVOICE_ATTENTION";

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceAttentionNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceNeedsAttention event) {
        // Invoices are the business's, not a warehouse's: the business-wide holders (null: scope ALL).
        List<StaffContact> recipients = staff.activeHolders(EInvoiceDueNotificationListener.AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("E-invoice {} needs attention ({}) and nobody may issue it", LogSafe.text(event.code()),
                    event.cause());
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("E-invoice {} needs attention ({}): {} recipient(s), {} newly told", LogSafe.text(event.code()),
                event.cause(), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceNeedsAttention event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String due = MOMENT.format(event.issueDueAt().atZone(BusinessCalendar.ZONE));
        String sent = MOMENT.format(event.submittedAt().atZone(BusinessCalendar.ZONE));
        String kind = switch (event.cause()) {
            case REJECTED -> "rejected";
            case STUCK_SUBMITTING -> "stuck";
            case UNKNOWN_TO_PROVIDER -> "unknown";
        };
        String key = "inbox.einvoice." + kind;
        String body = event.cause() == EInvoiceNeedsAttention.Cause.REJECTED
                ? texts.getString(key + ".body").formatted(event.code(), event.orderNumber(),
                        event.rejectionCode() == null ? "-" : event.rejectionCode(), due)
                : texts.getString(key + ".body").formatted(event.code(), event.orderNumber(), sent, due);
        // Stuck still resolves itself when the provider answers; refused and unknown do not, whoever waits.
        InboxSeverity severity = event.cause() == EInvoiceNeedsAttention.Cause.STUCK_SUBMITTING
                ? InboxSeverity.WARN : InboxSeverity.CRITICAL;
        return new InboxMessage(CATEGORY, severity, texts.getString(key + ".title").formatted(event.code()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/invoicing/einvoices/" + event.einvoiceId(), EInvoiceDueNotificationListener.SOURCE_TYPE,
                event.einvoiceId().toString(),
                "einvoice-" + kind + ":" + event.einvoiceId() + ":" + event.submittedAt().toEpochMilli());
    }
}
