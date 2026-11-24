package com.stockflow.notification.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceProviderAccountAlert;
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
 * Tells the people who issue e-invoices that the provider's account needs them on the provider's portal (HĐ-1;
 * kltn-docs 21 BR-09): the plan's invoices are running out or used up, the plan is unpaid, or the registration is not
 * ready. Without it a used-up plan looked like a slow provider: every invoice waited, and the accountant learnt only
 * that it was late.
 *
 * <p>The holders of {@link EInvoiceDueNotificationListener#AUDIENCE}, as for every e-invoice that needs them. <b>One
 * message per person, provider, cause and day</b> ({@code einvoice-account:<provider>:<problem>:<date>}): every invoice
 * refused for the same cause publishes the alert again, and the quota is looked at every sweep, but the accountant is
 * told once a day; the refused invoices are listed as REJECTED under the cause's code. System text only: codes and
 * numbers.</p>
 */
@Component
class EInvoiceProviderAccountNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceProviderAccountNotificationListener.class);

    static final String CATEGORY = "EINVOICE_PROVIDER_ACCOUNT";
    static final String SOURCE_TYPE = "EINVOICE_PROVIDER";

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceProviderAccountNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceProviderAccountAlert event) {
        List<StaffContact> recipients = staff.activeHolders(EInvoiceDueNotificationListener.AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("E-invoice provider {} needs attention ({}) and nobody may issue invoices",
                    LogSafe.text(event.provider()), event.problem());
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("E-invoice provider {} needs attention ({}): {} recipient(s), {} newly told",
                LogSafe.text(event.provider()), event.problem(), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceProviderAccountAlert event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = "inbox.einvoice.account." + event.problem().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String body = event.problem() == EInvoiceProviderAccountAlert.Problem.QUOTA_LOW
                ? texts.getString(key + ".body").formatted(event.provider(), event.quotaRemaining(),
                event.quotaThreshold())
                : texts.getString(key + ".body").formatted(event.provider(), event.rejectionCode());
        // A used-up plan holds the drafts back before they are sent (WF-05 BE-54, Q-22 BR-09): they wait as drafts.
        String link = switch (event.problem()) {
            case QUOTA_LOW -> "/invoicing/einvoices";
            case QUOTA_EXHAUSTED -> "/invoicing/einvoices?status=DRAFT";
            default -> "/invoicing/einvoices?status=REJECTED";
        };
        // Running low leaves time; the others stop every invoice now.
        InboxSeverity severity = event.problem() == EInvoiceProviderAccountAlert.Problem.QUOTA_LOW
                ? InboxSeverity.WARN : InboxSeverity.CRITICAL;
        return new InboxMessage(CATEGORY, severity,
                texts.getString(key + ".title").formatted(event.provider()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX), link,
                SOURCE_TYPE, event.provider(), "einvoice-account:" + event.provider() + ":" + event.problem() + ":"
                + BusinessCalendar.date(event.detectedAt()));
    }
}
