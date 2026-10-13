package com.stockflow.notification.internal.service;

import com.stockflow.common.security.Role;
import com.stockflow.contracts.ProductionOrderHeld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tells the people who can unblock a held production order, as the stories name them: Sales for a rejected
 * print file or a customer to wait for (Jira 396: "Sales is notified to handle a design change"), purchasing
 * for blanks short (Jira 398: "alert purchasing"), the Warehouse Manager for the rest (Jira 401: "Warehouse
 * Manager resolves").
 *
 * <p>The event names no person, so the notice goes to the team: every ACTIVE account holding the role, read
 * from {@link StaffDirectory}, by e-mail through the configured SMTP server. Warehouse Managers are the managers
 * who can see the production order's warehouse (ALL scope, or assigned to it): another warehouse's manager could
 * not even open it. Sales and purchasing work across warehouses and get it as a team. Asynchronous, after the hold
 * committed; a mail server that refuses leaves the event in the publication registry to be retried. With nobody
 * to write to, it says so in the log and completes - retrying cannot produce an address.</p>
 */
@Component
class ProductionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionNotificationListener.class);

    private final NotificationSender sender;
    private final StaffDirectory staff;

    ProductionNotificationListener(NotificationSender sender, StaffDirectory staff) {
        this.sender = sender;
        this.staff = staff;
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
        List<String> recipients = audience == Role.WAREHOUSE_MANAGER && event.warehouseId() != null
                ? staff.activeEmails(audience, event.warehouseId())
                : staff.activeEmails(audience);
        if (recipients.isEmpty()) {
            log.warn("Production order {} on hold ({}): no active {} account with an e-mail; nobody was notified",
                    event.orderNumber(), event.reasonCode(), audience);
            return;
        }
        sender.sendProductionHold(event, audience, recipients);
        log.info("Production order {} on hold ({}): notice e-mailed to {} {} account(s)", event.orderNumber(),
                event.reasonCode(), recipients.size(), audience);
    }
}
