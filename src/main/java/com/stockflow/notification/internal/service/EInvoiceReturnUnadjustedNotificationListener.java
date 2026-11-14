package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
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
 * Tells the people who issue e-invoices that a refunded return still owes a decrease adjustment they must draw up by
 * hand (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c.1): its goods are on no issued invoice that could take them
 * yet, or the invoice's rules refused the adjustment. Nothing else would bring it to anyone's eyes - the adjustment is
 * drawn up from an issued invoice, which may be issued days after the refund - and the return was refunded already.
 *
 * <p>The audience and the channel are the other invoicing notices' ({@link EInvoiceAttentionNotificationListener}): the
 * holders of {@link EInvoiceDueNotificationListener#AUDIENCE}, business-wide, in-app only. One message per person and
 * return ({@code einvoice-return-unadjusted:<return id>}), however often the event is delivered. System text only:
 * numbers, counts and an error code.</p>
 */
@Component
class EInvoiceReturnUnadjustedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceReturnUnadjustedNotificationListener.class);

    static final String CATEGORY = "EINVOICE_RETURN_UNADJUSTED";
    static final String SOURCE_TYPE = "RETURN_REQUEST";

    private final StaffDirectory staff;
    private final Inbox inbox;

    EInvoiceReturnUnadjustedNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(EInvoiceReturnUnadjusted event) {
        List<StaffContact> recipients = staff.activeHolders(EInvoiceDueNotificationListener.AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("Return {} owes an adjustment e-invoice drawn up by hand ({}) and nobody may draw it up",
                    LogSafe.text(event.rmaNumber()), event.cause());
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("Return {} owes an adjustment e-invoice drawn up by hand ({}): {} recipient(s), {} newly told",
                LogSafe.text(event.rmaNumber()), event.cause(), recipients.size(), told);
    }

    static InboxMessage message(EInvoiceReturnUnadjusted event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = event.cause() == EInvoiceReturnUnadjusted.Cause.REFUSED
                ? "inbox.einvoice.return-refused" : "inbox.einvoice.return-unadjusted";
        String body = event.cause() == EInvoiceReturnUnadjusted.Cause.REFUSED
                ? texts.getString(key + ".body").formatted(event.rmaNumber(), event.orderNumber(), event.reasonCode())
                : texts.getString(key + ".body").formatted(event.rmaNumber(), event.orderNumber(), event.pieces(),
                        event.orderLines());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString(key + ".title").formatted(event.rmaNumber()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/invoicing/einvoices?orderId=" + event.orderId(), SOURCE_TYPE, event.returnRequestId().toString(),
                "einvoice-return-unadjusted:" + event.returnRequestId());
    }
}
