package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.notification.api.CustomerDirectory;
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
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the buyer that an e-invoice was issued (WF-05 E5; plan §1.4 "Phát hành xong thì gửi email cho người mua kèm PDF,
 * XML và mã tra cứu"), two ways:
 *
 * <ol>
 *   <li><b>On the portal, always.</b> A notice in the portal account's inbox that the invoice is there to read and
 *       download. This does not depend on e-mail: with customer e-mail switched off
 *       ({@code stockflow.notification.email.customer.enabled}, {@code CUSTOMER_EMAIL_ENABLED}) it is how the buyer
 *       learns of the invoice, and the invoice itself is on the portal either way. One notice per invoice
 *       ({@code einvoice-issued:<id>}). A customer whose portal account is not activated has no inbox; nothing is
 *       posted, and the invoice is on the portal the day it signs in.</li>
 *   <li><b>By e-mail, when customer e-mail is on,</b> to the address the invoice names, with the PDF and the XML
 *       attached ({@link EInvoiceEmailComposer}). Sent once, logged and retried by the machinery every customer
 *       message uses ({@link CustomerEmailDelivery}, {@link CustomerEmailRetry}); switched off, it is recorded as
 *       skipped ({@code CUSTOMER_EMAIL_DISABLED}).</li>
 * </ol>
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

    private final CustomerDirectory customers;
    private final Inbox inbox;
    private final EInvoiceEmailComposer composer;
    private final CustomerEmailDelivery delivery;

    EInvoiceIssuedNotificationListener(CustomerDirectory customers, Inbox inbox, EInvoiceEmailComposer composer,
                                       CustomerEmailDelivery delivery) {
        this.customers = customers;
        this.inbox = inbox;
        this.composer = composer;
        this.delivery = delivery;
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
        composer.issued(event).ifPresent(delivery::deliver);
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
