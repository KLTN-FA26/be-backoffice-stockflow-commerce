package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.OrderCancellationReferred;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Decision 2026-10-17: the system did not cancel an order whose printing had started; the people who settle it by hand
 * are told - Sales (the agreed fee, with the customer) and the order coordinators (the order itself). In-app, one inbox
 * row per person per order and reason, so a redelivered event tells nobody twice; no e-mail (there is no template, and
 * the bell is where order work is picked up).
 */
@Component
class OrderReferralNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderReferralNotificationListener.class);

    private final StaffDirectory staff;
    private final StaffNotices notices;

    OrderReferralNotificationListener(StaffDirectory staff, StaffNotices notices) {
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(OrderCancellationReferred event) {
        Map<java.util.UUID, StaffContact> recipients = new LinkedHashMap<>();
        for (Role role : List.of(Role.SALES_STAFF, Role.ORDER_COORDINATOR)) {
            staff.activeStaff(role).forEach(contact -> recipients.putIfAbsent(contact.userId(), contact));
        }
        if (recipients.isEmpty()) {
            log.warn("Order {} referred to staff ({}): no active Sales or coordinator account; nobody was notified",
                    LogSafe.text(event.orderNumber()), LogSafe.text(event.reasonCode()));
            return;
        }
        int told = notices.deliver(new ArrayList<>(recipients.values()), message(event), emails -> { });
        log.info("Order {} referred to staff ({}): {} account(s), {} newly told", LogSafe.text(event.orderNumber()),
                LogSafe.text(event.reasonCode()), recipients.size(), told);
    }

    /** System text only: the order number. */
    static InboxMessage message(OrderCancellationReferred event) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        return new InboxMessage("ORDER_ATTENTION", InboxSeverity.WARN,
                messages.getString("inbox.order.cancellation.referred.title").formatted(event.orderNumber()),
                messages.getString("inbox.order.cancellation.referred.body"), "/admin/orders/" + event.orderId(),
                "ORDER", event.orderId().toString(),
                "order-cancellation-referred:%s:%s".formatted(event.orderId(), event.reasonCode()));
    }
}
