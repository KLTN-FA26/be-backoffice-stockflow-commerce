package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
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
 * Tells the buyer that an e-invoice issued to it names it wrongly (WF-05 E4/E5; Mẫu 04/SS-HĐĐT, TT 91/2026/TT-BTC
 * Điều 10 khoản 1 điểm a): its name or address is wrong, its tax code right, the invoice stays valid and is not issued
 * again, and the tax authority is notified of the right details. The law has the buyer told; the two ways are the
 * issued invoice's ({@link EInvoiceIssuedNotificationListener}), for the same reasons:
 *
 * <ol>
 *   <li><b>On the portal, always</b>: a notice in the portal account's inbox, linking to the invoice, beside which the
 *       portal lists the notice itself ({@code GET /api/v1/einvoices/me/{id}/error-notices}). One per notice
 *       ({@code einvoice-error-notice:<notice id>}). A customer with no activated portal account has no inbox.</li>
 *   <li><b>By e-mail, when customer e-mail is on,</b> to the address the invoice names, with the right name and
 *       address and the reason ({@link EInvoiceEmailComposer#errorNoticeRecorded}); no file, since the invoice is not
 *       issued again. Sent once, logged and retried by {@link CustomerEmailDelivery} and {@link CustomerEmailRetry}.</li>
 * </ol>
 *
 * <p>The notice first, so a mail provider that is down does not keep the portal silent. A redelivery posts nothing
 * twice and sends nothing twice. {@code @Async @TransactionalEventListener} rather than
 * {@code @ApplicationModuleListener}: no transaction is held across the call to the mail provider. Public so the
 * proxy advises it.</p>
 */
@Component
class EInvoiceErrorNoticeNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceErrorNoticeNotificationListener.class);

    static final String CATEGORY = "EINVOICE_ERROR_NOTICE";
    static final String SOURCE_TYPE = "EINVOICE";

    private final CustomerDirectory customers;
    private final Inbox inbox;
    private final EInvoiceEmailComposer composer;
    private final CustomerEmailDelivery delivery;

    EInvoiceErrorNoticeNotificationListener(CustomerDirectory customers, Inbox inbox, EInvoiceEmailComposer composer,
                                            CustomerEmailDelivery delivery) {
        this.customers = customers;
        this.inbox = inbox;
        this.composer = composer;
        this.delivery = delivery;
    }

    @Async
    @TransactionalEventListener
    public void on(EInvoiceErrorNoticeRecorded event) {
        UUID account = event.customerId() == null ? null : customers.portalAccount(event.customerId()).orElse(null);
        if (account == null) {
            log.info("Error notice of e-invoice {} recorded; its customer has no portal account to tell",
                    LogSafe.text(event.code()));
        } else {
            int told = inbox.post(List.of(account), message(event));
            log.info("Error notice of e-invoice {} recorded: customer {} on the portal", LogSafe.text(event.code()),
                    told == 1 ? "told" : "already told");
        }
        composer.errorNoticeRecorded(event).ifPresent(delivery::deliver);
    }

    /** System text only: the order's number. The right name and address are read on the portal page. */
    static InboxMessage message(EInvoiceErrorNoticeRecorded event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.einvoice.error-notice.title").formatted(event.orderNumber()),
                texts.getString("inbox.einvoice.error-notice.body").formatted(event.orderNumber()),
                "/einvoices/" + event.einvoiceId(), SOURCE_TYPE, event.einvoiceId().toString(),
                "einvoice-error-notice:" + event.noticeId());
    }
}
