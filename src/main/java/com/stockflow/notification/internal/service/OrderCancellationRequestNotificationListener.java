package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.OrderCancellationRequested;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * A customer asked to cancel their order (kltn-docs 17 §4.8 step 28, "Hệ thống báo Sale"): whoever decides cancellation
 * requests hears of it - a bell and an e-mail - with a link to the requests page, instead of finding it there by chance.
 * Sent after commit; redelivered harmlessly (the inbox dedupes on the request).
 */
@Component
class OrderCancellationRequestNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationRequestNotificationListener.class);

    static final String CATEGORY = "ORDER_CANCELLATION_REQUEST";
    static final String SOURCE_TYPE = "ORDER";
    /** Who decides a cancellation request (CancellationRequestController approve / reject). */
    static final String DECIDES = "sales-order-cancellations:UPDATE";
    static final String LINK = "/admin/orders/cancellation-requests";

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    OrderCancellationRequestNotificationListener(NotificationSender sender, StaffDirectory staff,
                                                 StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(OrderCancellationRequested event) {
        List<StaffContact> recipients = staff.activeHolders(DECIDES, null);
        if (recipients.isEmpty()) {
            log.warn("Cancellation request {}: nobody active holds {}; nobody was notified", event.requestId(), DECIDES);
            return;
        }
        InboxMessage message = message(event);
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_order_cancellation_request"));
        log.info("Cancellation request {}: {} person(s), {} newly told", event.requestId(), recipients.size(), told);
    }

    static InboxMessage message(OrderCancellationRequested event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String number = LogSafe.text(event.orderNumber());
        String key = "label.CancellationReasonCode." + event.reasonCode();
        String reason = texts.containsKey(key) ? texts.getString(key) : LogSafe.text(event.reasonCode());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.order.cancellationRequest.title").formatted(number),
                texts.getString("inbox.order.cancellationRequest.body").formatted(number, reason), LINK, SOURCE_TYPE,
                event.orderId().toString(), "order-cancellation-request:" + event.requestId());
    }
}
