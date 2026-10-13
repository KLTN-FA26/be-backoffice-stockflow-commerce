package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tells the right staff about new stock alerts (SCRUM-147): one in-app message per recipient per kind, warehouse and
 * day, built from the {@link InventoryAlertsRaised} facts.
 *
 * <p><b>Who.</b> By <i>staff role</i>, never by permission and never a customer (plan A6):</p>
 * <ul>
 *   <li>REORDER and SAFETY are about a SKU for the whole system: the inventory planner (docs 00: follows stock between
 *       warehouses) and SYSTEM_ADMIN, whose scope is ALL. Not procurement: the planner turns the proposal into a
 *       purchase order and tells procurement (docs 02 step 2).</li>
 *   <li>EXPIRING and EXPIRED belong to one warehouse: the warehouse manager of that warehouse (flow F-EXP "báo quản lý
 *       kho") and the planner and administrator, who see every warehouse.</li>
 * </ul>
 * <p>Both lists are configuration ({@code stockflow.notification.inventory-alert.*-roles}); the scope rule (ALL, or
 * assigned to the warehouse) and the exclusion of customers are in {@code IdentityService.notificationRecipients}.</p>
 *
 * <p><b>E-mail</b> is off ({@code stockflow.notification.inventory-alert-email.enabled=false}, open question Q3). When
 * on, a person who is newly given the in-app message also gets the same text by e-mail, so the once-per-day rule holds
 * for both and a redelivered event does not mail twice. A failing mail server never fails the event.</p>
 *
 * <p>Titles and bodies are never logged; only counts and ids are.</p>
 */
@Component
class InventoryAlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryAlertNotificationListener.class);

    private final Inbox inbox;
    private final IdentityService identities;
    private final WarehouseService warehouses;
    private final NotificationSender sender;
    private final boolean emailEnabled;
    private final Set<String> stockLevelRoles;
    private final Set<String> expiryRoles;
    private final Locale locale;

    InventoryAlertNotificationListener(
            Inbox inbox, IdentityService identities, WarehouseService warehouses, NotificationSender sender,
            @Value("${stockflow.notification.inventory-alert-email.enabled:false}") boolean emailEnabled,
            @Value("${stockflow.notification.inventory-alert.stock-level-roles:INVENTORY_PLANNER,SYSTEM_ADMIN}") String stockLevelRoles,
            @Value("${stockflow.notification.inventory-alert.expiry-roles:WAREHOUSE_MANAGER,INVENTORY_PLANNER,SYSTEM_ADMIN}") String expiryRoles,
            @Value("${stockflow.notification.inbox-locale:vi}") String locale) {
        this.inbox = inbox;
        this.identities = identities;
        this.warehouses = warehouses;
        this.sender = sender;
        this.emailEnabled = emailEnabled;
        this.stockLevelRoles = roles(stockLevelRoles);
        this.expiryRoles = roles(expiryRoles);
        this.locale = Locale.forLanguageTag(locale);
    }

    private static Set<String> roles(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @ApplicationModuleListener
    public void on(InventoryAlertsRaised event) {
        boolean stockLevel = "REORDER".equals(event.kind()) || "SAFETY".equals(event.kind());
        List<StaffContact> recipients = identities.notificationRecipients(stockLevel ? stockLevelRoles : expiryRoles,
                stockLevel ? null : event.warehouseId());
        if (recipients.isEmpty()) {
            // The alerts exist and are on /inventory/alerts; there is simply nobody to tell.
            log.warn("Stock alert batch {} ({}) has no recipient", event.batchId(), LogSafe.text(event.kind()));
            return;
        }
        String warehouseCode = event.warehouseId() == null ? null
                : warehouses.findWarehouse(event.warehouseId()).map(WarehouseView::prefix).orElse(null);
        InboxMessage message = InventoryAlertMessages.build(event, warehouseCode, locale);

        int told = 0;
        for (StaffContact recipient : recipients) {
            // One at a time: the count says who is newly told, and only they are e-mailed.
            if (inbox.post(List.of(recipient.userId()), message) == 1) {
                told++;
                if (emailEnabled) {
                    email(recipient, message);
                }
            }
        }
        log.info("Stock alert batch {} ({}): {} item(s), {} recipient(s), {} newly told", event.batchId(),
                LogSafe.text(event.kind()), event.items().size(), recipients.size(), told);
    }

    private void email(StaffContact recipient, InboxMessage message) {
        if (recipient.email() == null || recipient.email().isBlank()) {
            return;
        }
        try {
            sender.sendInventoryAlertEmail(recipient.email(), message.title(), message.body());
        } catch (RuntimeException failure) {
            // Best effort: the in-app message is the record; a mail outage must not fail or replay the event.
            log.warn("Stock alert e-mail to user {} failed ({})", recipient.userId(), failure.getClass().getSimpleName());
        }
    }
}
