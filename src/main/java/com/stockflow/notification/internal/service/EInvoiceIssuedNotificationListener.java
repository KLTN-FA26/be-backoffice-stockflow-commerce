package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.EInvoiceDirectory;
import com.stockflow.notification.api.EInvoiceMail;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the buyer that an e-invoice was issued (WF-05 E5; plan §1.4 "Phát hành xong thì gửi email cho người mua kèm PDF,
 * XML và mã tra cứu"), two ways:
 *
 * <ol>
 *   <li><b>On the portal, always.</b> A notice in the portal account's inbox that the invoice is there to read and
 *       download. This does not depend on e-mail: with e-invoice e-mail switched off
 *       ({@code stockflow.notification.email.customer.einvoice-enabled}, {@code EINVOICE_EMAIL_ENABLED}) it is how the
 *       buyer learns of the invoice, and the invoice itself is on the portal either way. One notice per invoice
 *       ({@code einvoice-issued:<id>}). A customer whose portal account is not activated has no inbox; nothing is
 *       posted, and the invoice is on the portal the day it signs in.</li>
 *   <li><b>By e-mail, when e-invoice e-mail is on</b> - its own switch, on unless configured otherwise, apart from the
 *       order mail's - to the address the invoice names, with the PDF and the XML attached
 *       ({@link EInvoiceEmailComposer}). Sent once, logged and retried by the machinery every customer message uses
 *       ({@link CustomerEmailDelivery}, {@link CustomerEmailRetry}); switched off, it is recorded as skipped
 *       ({@code EINVOICE_EMAIL_DISABLED}).</li>
 * </ol>
 *
 * <p><b>Issued without a buyer e-mail</b> (HĐ-8; WF-05 Q-22 BR-06): a missing e-mail no longer holds an invoice back -
 * it is not invoice content (NĐ 254/2026/NĐ-CP Điều 10 khoản 1) and the invoice goes out on its day - so the mail has
 * nowhere to go and is recorded as skipped. Sales, who keep the customer's legal profile, are told in their inbox
 * (once per invoice, {@code einvoice-buyer-email-missing:<id>}) to record the e-mail and send the invoice on; the
 * buyer still has it on the portal.</p>
 *
 * <p>The notice first: an e-mail attempt worth retrying throws to keep the publication for the retry, and the buyer
 * must not wait for a mail provider to come back before the portal says anything. A redelivery posts nothing twice and
 * sends nothing twice.</p>
 *
 * <p>{@code @Async @TransactionalEventListener}, not {@code @ApplicationModuleListener}, for the reason
 * {@link OrderNotificationListener} gives: no transaction is held across the call to the mail provider. The handler is
 * public so the proxy advises it.</p>
 */
@Component
class EInvoiceIssuedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceIssuedNotificationListener.class);

    static final String CATEGORY = "EINVOICE_ISSUED";
    static final String SOURCE_TYPE = "EINVOICE";
    static final String EMAIL_MISSING_CATEGORY = "EINVOICE_BUYER_EMAIL_MISSING";
    /** Who records the missing e-mail: the customer's legal profile, as for a draft's missing data. */
    static final String EMAIL_MISSING_AUDIENCE = EInvoiceBuyerIncompleteNotificationListener.AUDIENCE;

    private final CustomerDirectory customers;
    private final Inbox inbox;
    private final EInvoiceEmailComposer composer;
    private final CustomerEmailDelivery delivery;
    private final EInvoiceDirectory einvoices;
    private final StaffDirectory staff;

    EInvoiceIssuedNotificationListener(CustomerDirectory customers, Inbox inbox, EInvoiceEmailComposer composer,
                                       CustomerEmailDelivery delivery, EInvoiceDirectory einvoices,
                                       StaffDirectory staff) {
        this.customers = customers;
        this.inbox = inbox;
        this.composer = composer;
        this.delivery = delivery;
        this.einvoices = einvoices;
        this.staff = staff;
    }

    @Async
    @TransactionalEventListener
    public void on(EInvoiceIssued event) {
        UUID account = event.customerId() == null ? null : customers.portalAccount(event.customerId()).orElse(null);
        if (account == null) {
            log.info("E-invoice {} issued; its customer has no portal account to tell", LogSafe.text(event.code()));
        } else {
            int told = inbox.post(List.of(account), message(event));
            log.info("E-invoice {} issued: customer {} on the portal", LogSafe.text(event.code()),
                    told == 1 ? "told" : "already told");
        }
        tellSalesIfNoEmail(event);
        composer.issued(event).ifPresent(delivery::deliver);
    }

    /** Before the mail is handed over: a mail attempt kept for the retry throws, and Sales must hear either way. */
    private void tellSalesIfNoEmail(EInvoiceIssued event) {
        Optional<EInvoiceMail> mail = einvoices.mail(event.einvoiceId());
        if (mail.isEmpty() || mail.get().recipientEmail() != null && !mail.get().recipientEmail().isBlank()) {
            return;
        }
        List<UUID> recipients = staff.activeHolders(EMAIL_MISSING_AUDIENCE, null).stream().map(StaffContact::userId)
                .filter(Objects::nonNull).toList();
        if (recipients.isEmpty()) {
            log.warn("E-invoice {} was issued without a buyer e-mail and nobody may record one",
                    LogSafe.text(event.code()));
            return;
        }
        int told = inbox.post(recipients, emailMissing(event));
        log.info("E-invoice {} was issued without a buyer e-mail: {} recipient(s), {} newly told",
                LogSafe.text(event.code()), recipients.size(), told);
    }

    /** System text only: the invoice's code and the order's number. */
    static InboxMessage emailMissing(EInvoiceIssued event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        return new InboxMessage(EMAIL_MISSING_CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.einvoice.buyer-email-missing.title").formatted(event.orderNumber()),
                texts.getString("inbox.einvoice.buyer-email-missing.body").formatted(event.code(),
                        event.orderNumber()),
                event.customerId() == null ? "/einvoices/" + event.einvoiceId() : "/customers/" + event.customerId(),
                event.customerId() == null ? SOURCE_TYPE : "CUSTOMER_PROFILE",
                (event.customerId() == null ? event.einvoiceId() : event.customerId()).toString(),
                "einvoice-buyer-email-missing:" + event.einvoiceId());
    }

    /** System text only: the order's number. The invoice's number and amounts are read on the portal page. */
    static InboxMessage message(EInvoiceIssued event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                texts.getString("inbox.einvoice.issued.title").formatted(event.orderNumber()),
                texts.getString("inbox.einvoice.issued.body").formatted(event.orderNumber()),
                "/einvoices/" + event.einvoiceId(), SOURCE_TYPE, event.einvoiceId().toString(),
                "einvoice-issued:" + event.einvoiceId());
    }
}
