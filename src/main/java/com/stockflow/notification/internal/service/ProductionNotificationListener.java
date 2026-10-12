package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ProductionOrderHeld;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells the people who can unblock a held production order (SCRUM-396/398/401): Sales for a rejected print
 * file or a customer to wait for (Jira 396: "Sales is notified to handle a design change"), purchasing for
 * blanks short (Jira 398: "alert purchasing"), the workshop's manager for the rest.
 *
 * <p>Delivered through {@link NotificationSender#send}, which today records the message in the log as every
 * template but the purchase order does; the routing is what this class decides, and it does not change when a
 * real channel arrives. Asynchronous, after the hold committed, retried from the publication registry.</p>
 */
@Component
class ProductionNotificationListener {

    private final NotificationSender sender;

    ProductionNotificationListener(NotificationSender sender) {
        this.sender = sender;
    }

    @ApplicationModuleListener
    public void on(ProductionOrderHeld event) {
        String audience = switch (event.reason()) {
            case "FILE_REJECTED", "WAITING_CUSTOMER" -> "sales";
            case "BLANKS_SHORT" -> "purchasing";
            default -> "workshop";
        };
        String source = event.sampleRequestId() != null
                ? "sample request " + event.sampleRequestId()
                : "order " + event.salesOrderId();
        sender.send(null, "production.held." + audience,
                "Production order %s on hold: %s".formatted(event.orderNumber(), event.reason()),
                "Production order %s (%s) is on hold, %s%s.".formatted(event.orderNumber(), source, event.reason(),
                        event.note() == null ? "" : ": " + event.note()));
    }
}
