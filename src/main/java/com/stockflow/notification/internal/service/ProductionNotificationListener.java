package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells the people who can unblock a held production order, as the stories name them: Sales for a rejected
 * print file or a customer to wait for (Jira 396: "Sales is notified to handle a design change"), purchasing
 * for blanks short (Jira 398: "alert purchasing"), the Warehouse Manager for the rest (Jira 401: "Warehouse
 * Manager resolves").
 *
 * <p>The event names no person, so the notice goes to the team ({@link StaffDirectory}): every ACTIVE account of the
 * role; for Warehouse Managers, the managers who can see the order's warehouse (ALL scope, or assigned to it) -
 * another warehouse's manager could not even open it. Sales and purchasing work across warehouses.</p>
 *
 * <p>Delivered in-app first, then e-mailed best effort ({@link StaffNotices}): one inbox row per person per hold
 * ({@code production-hold:<order>:<reason>:<heldAt>}), so a redelivered event tells nobody twice and an environment
 * without SMTP still shows the notice in the bell. With nobody to tell it says so in the log and completes.</p>
 */
@Component
class ProductionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionNotificationListener.class);

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    ProductionNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    /** Who acts on a hold of this reason. */
    static Role audience(String reason) {
        return switch (reason) {
            case "FILE_REJECTED", "WAITING_CUSTOMER" -> Role.SALES_STAFF;
            case "BLANKS_SHORT" -> Role.PROCUREMENT_STAFF;
            default -> Role.WAREHOUSE_MANAGER;
        };
    }

    @ApplicationModuleListener
    public void on(ProductionOrderHeld event) {
        Role audience = audience(event.reasonCode());
        List<StaffContact> recipients = audience == Role.WAREHOUSE_MANAGER && event.warehouseId() != null
                ? staff.activeStaff(audience, event.warehouseId())
                : staff.activeStaff(audience);
        if (recipients.isEmpty()) {
            log.warn("Production order {} on hold ({}): no active {} account; nobody was notified",
                    LogSafe.text(event.orderNumber()), LogSafe.text(event.reasonCode()), audience);
            return;
        }
        int told = notices.deliver(recipients, message(event),
                emails -> sender.sendProductionHold(event, audience, emails));
        log.info("Production order {} on hold ({}): {} {} account(s), {} newly told", LogSafe.text(event.orderNumber()),
                LogSafe.text(event.reasonCode()), recipients.size(), audience, told);
    }

    /** System text only: the order number, the reason's label, the blanks count. */
    static InboxMessage message(ProductionOrderHeld event) {
        var messages = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = "production.hold.reason." + event.reasonCode();
        String reason = messages.containsKey(key) ? messages.getString(key) : event.reasonCode();
        String body = event.blanksNeeded() == null || event.blankSku() == null ? null
                : messages.getString("production.hold.blanks").formatted(event.blanksNeeded(), event.blankSku()).strip();
        return new InboxMessage("PRODUCTION_HOLD", InboxSeverity.WARN,
                messages.getString("inbox.production.hold.title").formatted(event.orderNumber(), reason), body,
                "/admin/production/orders/" + event.productionOrderId(), "PRODUCTION_ORDER",
                event.productionOrderId().toString(),
                "production-hold:%s:%s:%s".formatted(event.productionOrderId(), event.reasonCode(), event.heldAt()));
    }
}
