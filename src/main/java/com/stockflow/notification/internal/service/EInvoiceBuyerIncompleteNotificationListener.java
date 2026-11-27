package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceBuyerIncomplete;
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
import java.util.stream.Collectors;

/**
 * Tells Sales that an e-invoice cannot be issued until the customer's profile has what it lacks (HĐ-8; kltn-docs 21
 * BR-06 and §4.2 step 4: "thiếu thì chặn, báo Sale bổ sung"): the name, tax code or address, or the e-mail that receives
 * the invoice. Sales keep the customer's profile; the accountant can only refresh the buyer from it once it is there,
 * and the invoice's deadline runs meanwhile. An in-app message, nothing by e-mail.
 *
 * <p>The holders of {@value #AUDIENCE}, who record the customer's legal profile and invoice e-mail. One message per
 * person, invoice and set of missing details ({@code einvoice-buyer-incomplete:<id>:<missing>}): a refresh that still
 * finds the same gap tells nobody again; one that finds another does. System text only - codes and the order's
 * number.</p>
 */
@Component
class EInvoiceBuyerIncompleteNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceBuyerIncompleteNotificationListener.class);

    static final String CATEGORY = "EINVOICE_BUYER_INCOMPLETE";
    static final String SOURCE_TYPE = "CUSTOMER_PROFILE";
    /** Who records what is missing: the customer's legal profile, invoice e-mail included. */
    static final String AUDIENCE = "customer-legal-profiles:UPDATE";

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceBuyerIncompleteNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceBuyerIncomplete event) {
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("E-invoice {} waits for the customer's profile ({}) and nobody may complete it",
                    LogSafe.text(event.code()), event.missing());
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("E-invoice {} waits for the customer's profile ({}): {} recipient(s), {} newly told",
                LogSafe.text(event.code()), event.missing(), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceBuyerIncomplete event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<EInvoiceBuyerIncomplete.Missing> missing = event.missing().stream().sorted().toList();
        String what = missing.stream()
                .map(field -> texts.getString("inbox.einvoice.buyer-incomplete.missing." + field.name()))
                .collect(Collectors.joining(", "));
        String body = texts.getString("inbox.einvoice.buyer-incomplete.body").formatted(event.code(),
                event.orderNumber(), what);
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.einvoice.buyer-incomplete.title").formatted(event.orderNumber()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                // The back office's customer page (audit r3 EINV-6: "/customers/..." led nowhere there).
                "/admin/customers/" + event.customerId(), SOURCE_TYPE, event.customerId().toString(),
                "einvoice-buyer-incomplete:" + event.einvoiceId() + ":"
                        + missing.stream().map(Enum::name).collect(Collectors.joining(",")));
    }
}
