package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.PaymentDueAtPacking;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the warehouse managers of the shipment's warehouse and the Sales who look after the order's customer that the
 * carton check completed a shipment while an installment due before delivery is still unpaid (kltn-docs 08 §4.4 step 9,
 * BR-11, §2, §3; owner decision RTM-PAYNOTIFY, 2026-11-19: "thông báo trong app cho QLK kho và Sale phụ trách đơn"): an
 * in-app message at level WARN (owner decision RTM-PAYNOTIFY-LEVEL, 2026-11-19), nothing by e-mail. The handover stays blocked until it is paid (09); this only tells.
 *
 * <p>The warehouse managers are chosen by permission, not by role name: whoever approves at the carton check station
 * ({@value #AUDIENCE}, the manager's permission there, as for a foreign carton) and can see the shipment's warehouse.
 * Of Sales, whoever looks after the order's customer today ({@link SalesNotices}; 08 §4.4 step 9 and 00 §6 as changed on
 * 2026-11-28): its owner or the owner's cover, else the Sales Managers with the notice marked "cần phân công" - asked
 * now, not taken from the event's {@code salesUserId}.</p>
 *
 * <p>One message per person per shipment ({@code payment-due-at-packing:<shipment>}): a redelivered event tells nobody
 * twice. System text only - codes and the amount. Titles and bodies are never logged.</p>
 */
@Component
class PaymentDueAtPackingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentDueAtPackingNotificationListener.class);

    static final String CATEGORY = "PAYMENT_DUE_AT_PACKING";
    static final String SOURCE_TYPE = "SHIPMENT";
    /** Who works another's carton check session and decides its exceptions: the warehouse manager (08 BR-06). */
    static final String AUDIENCE = "fulfillment-packing:APPROVE";

    private static final Locale VI = Locale.forLanguageTag("vi");

    private final StaffDirectory staff;
    private final SalesNotices sales;
    private final Inbox inbox;

    PaymentDueAtPackingNotificationListener(StaffDirectory staff, SalesNotices sales, Inbox inbox) {
        this.staff = staff;
        this.sales = sales;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(PaymentDueAtPacking event) {
        List<UUID> managers = staff.activeHolders(AUDIENCE, event.warehouseId()).stream().map(StaffContact::userId)
                .filter(Objects::nonNull).distinct().toList();
        SalesAudience audience = sales.audiences().ofOrder(event.orderId()).orElse(null);
        List<UUID> salespeople = audience == null ? List.of() : audience.userIds().stream()
                .filter(id -> !managers.contains(id)).toList();
        if (managers.isEmpty() && salespeople.isEmpty()) {
            log.warn("Shipment {} of order {} left an installment unpaid before delivery; nobody to tell",
                    LogSafe.text(event.shipmentCode()), LogSafe.text(event.orderNumber()));
            return;
        }
        InboxMessage message = message(event);
        int told = inbox.post(managers, message)
                + inbox.post(salespeople, SalesNotices.marked(message, audience != null && audience.needsAssignment()));
        log.info("Shipment {} of order {} with an installment unpaid before delivery: {} recipient(s), {} newly told",
                LogSafe.text(event.shipmentCode()), LogSafe.text(event.orderNumber()),
                managers.size() + salespeople.size(), told);
    }

    static InboxMessage message(PaymentDueAtPacking event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", VI);
        String amount = NumberFormat.getIntegerInstance(VI).format(event.amountDue()) + " " + event.currency();
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.packing.payment-due.title").formatted(event.orderNumber()),
                texts.getString("inbox.packing.payment-due.body").formatted(event.shipmentCode(),
                        event.orderNumber(), amount),
                "/admin/orders/" + event.orderId(), SOURCE_TYPE, event.shipmentId().toString(),
                "payment-due-at-packing:" + event.shipmentId());
    }
}
