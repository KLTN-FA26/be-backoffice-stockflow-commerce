package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ForeignCartonScanned;
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
 * Tells the warehouse manager that a foreign carton was scanned at the carton check station (kltn-docs 08 §4.2 edge
 * case: a carton of another order, or a code that cannot be read, is refused and "báo Quản lý kho";
 * WF4-LPN-FOREIGN): an in-app message, nothing by e-mail. The scan is refused as before; this only tells. The audience
 * is whoever approves at the carton check station ({@value #AUDIENCE}, the manager's permission there) and can see the
 * task's warehouse, so the role matrix decides who that is.
 *
 * <p>One message per person per task and scanned code ({@code foreign-carton:<task>:<lpn>}): the same wrong carton
 * scanned again does not tell twice. A code that is not a carton may be anything typed or printed, so it is shown cut
 * to {@value ForeignCartonScanned#LPN_MAX} characters and never logged.</p>
 */
@Component
class ForeignCartonNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ForeignCartonNotificationListener.class);

    static final String CATEGORY = "CARTON_CHECK";
    static final String SOURCE_TYPE = "FULFILLMENT_TASK";
    /** Who works another's carton check session and decides its exceptions (CartonCheckService, 08 BR-06). */
    static final String AUDIENCE = "fulfillment-packing:APPROVE";

    private final StaffDirectory staff;
    private final Inbox inbox;

    ForeignCartonNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(ForeignCartonScanned event) {
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, event.warehouseId());
        if (recipients.isEmpty()) {
            log.warn("A foreign carton was scanned for order {}; nobody at its warehouse can be told",
                    LogSafe.text(event.orderNumber()));
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("A foreign carton was scanned for order {}: {} recipient(s), {} newly told",
                LogSafe.text(event.orderNumber()), recipients.size(), told);
    }

    static InboxMessage message(ForeignCartonScanned event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String lpn = event.lpn() == null ? "" : event.lpn();
        String body = texts.getString("inbox.carton.foreign.body").formatted(lpn, event.orderNumber());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.carton.foreign.title").formatted(event.orderNumber()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/admin/orders/" + event.orderId(), SOURCE_TYPE, event.taskId().toString(),
                "foreign-carton:%s:%s".formatted(event.taskId(), lpn));
    }
}
