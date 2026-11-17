package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.WarehouseCapacityTurnedNegative;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the warehouse manager(s) of a warehouse that a production order just sent takes its projected free room below
 * zero (BE1; kltn-docs 06 §4.6 step 8, BR-21; 00 §6 "Còn trống dự kiến của kho chuyển sang âm"): WARN, in the in-app
 * inbox only - 00 §6 sends no e-mail for it - with the production order and how far below zero, linking to the
 * warehouse's capacity board. The order module publishes the event only at the crossing (C42), so a warehouse already
 * short is not told again on every production order after it.
 *
 * <p><b>A role, not a permission.</b> The notices of this module go to the holders of a permission (who is told follows
 * the role matrix), but C42 decided this one goes to the warehouse manager of the receiving warehouse and to nobody
 * else - not Sales, not the Sales Manager - and no permission singles them out: {@code warehouse-capacity:READ} is held
 * by Sales and the Production Coordinator as well, {@code warehouse-warehouses:UPDATE} by the System Admin of every
 * warehouse. So the recipients are the ACTIVE warehouse managers who can see that warehouse (assigned to it, or of
 * every warehouse). A redelivered event tells nobody twice ({@code capacity-negative:<warehouse>:<production order>}).</p>
 */
@Component
class WarehouseCapacityNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(WarehouseCapacityNotificationListener.class);

    static final String CATEGORY = "WAREHOUSE_CAPACITY";
    static final String SOURCE_TYPE = "WAREHOUSE";

    private final StaffDirectory staff;
    private final Inbox inbox;
    private final WarehouseService warehouses;

    WarehouseCapacityNotificationListener(StaffDirectory staff, Inbox inbox, WarehouseService warehouses) {
        this.staff = staff;
        this.inbox = inbox;
        this.warehouses = warehouses;
    }

    @ApplicationModuleListener
    public void on(WarehouseCapacityTurnedNegative event) {
        List<UUID> managers = staff.activeStaff(Role.WAREHOUSE_MANAGER, event.warehouseId()).stream()
                .map(StaffContact::userId).filter(Objects::nonNull).distinct().toList();
        if (managers.isEmpty()) {
            log.info("Production order {} takes warehouse {} below zero: no warehouse manager to tell",
                    LogSafe.text(event.productionOrderCode()), event.warehouseId());
            return;
        }
        String warehouse = warehouses.findWarehouse(event.warehouseId()).map(WarehouseView::name)
                .orElse(event.warehouseId().toString());
        int told = inbox.post(managers, message(event, warehouse));
        log.info("Production order {} takes warehouse {} below zero: {} warehouse manager(s) told",
                LogSafe.text(event.productionOrderCode()), event.warehouseId(), told);
    }

    static InboxMessage message(WarehouseCapacityTurnedNegative event, String warehouse) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String title = texts.getString("inbox.capacity.negative.title").formatted(warehouse);
        String shortfall = event.shortfallM3().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                title.length() <= InboxMessage.TITLE_MAX ? title : title.substring(0, InboxMessage.TITLE_MAX),
                texts.getString("inbox.capacity.negative.body").formatted(event.productionOrderCode(), warehouse,
                        shortfall),
                "/warehouse/capacity/" + event.warehouseId(), SOURCE_TYPE, event.warehouseId().toString(),
                "capacity-negative:" + event.warehouseId() + ":" + event.productionOrderId());
    }
}
