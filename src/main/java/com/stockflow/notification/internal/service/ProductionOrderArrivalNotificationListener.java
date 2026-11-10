package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ProductionOrderSent;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the warehouse manager(s) of the receiving warehouse that a production order was sent, so goods are coming
 * (4A-3; kltn-docs 00 §6 "Đơn sản xuất được gửi về kho", 17 §4.3 step 7, BR-25): INFO, with the cartons, their volume
 * and the need-by date, in the in-app inbox only - 00 §6 sends no e-mail for it. Who is told is decided when it is
 * delivered: the ACTIVE staff who can see that warehouse (assigned to it, or of every warehouse) and hold
 * {@link #AUDIENCE} - those who confirm what arrives, the warehouse manager in today's role matrix. A permission, not a
 * role name (house rule P8): who is told follows the matrix as it is edited. A redelivered event tells nobody twice
 * ({@code production-order-sent:<id>}).
 *
 * <p>The WARN when the warehouse's expected free space turns negative (C42) waits for the capacity table, as the
 * capacity warnings of 4A-2 do.</p>
 */
@Component
class ProductionOrderArrivalNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderArrivalNotificationListener.class);

    static final String CATEGORY = "PRODUCTION_ORDER";
    static final String SOURCE_TYPE = "PRODUCTION_ORDER";
    /** Who confirms the goods a warehouse receives: told what is coming. */
    static final String AUDIENCE = "procurement-goods-receipts:APPROVE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;
    private final WarehouseService warehouses;

    ProductionOrderArrivalNotificationListener(StaffDirectory staff, Inbox inbox, WarehouseService warehouses) {
        this.staff = staff;
        this.inbox = inbox;
        this.warehouses = warehouses;
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSent event) {
        List<UUID> managers = staff.activeHolders(AUDIENCE, event.receivingWarehouseId()).stream()
                .map(StaffContact::userId).filter(Objects::nonNull).distinct().toList();
        if (managers.isEmpty()) {
            log.info("Production order {} sent: nobody at its receiving warehouse to tell",
                    LogSafe.text(event.code()));
            return;
        }
        String warehouse = warehouses.findWarehouse(event.receivingWarehouseId()).map(WarehouseView::name)
                .orElse(event.receivingWarehouseId().toString());
        int told = inbox.post(managers, message(event, warehouse));
        log.info("Production order {} sent: {} warehouse manager(s) told", LogSafe.text(event.code()), told);
    }

    static InboxMessage message(ProductionOrderSent event, String warehouse) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String volume = event.volumeM3() == null ? texts.getString("inbox.production-order.volume.unknown")
                : texts.getString("inbox.production-order.volume")
                .formatted(event.volumeM3().setScale(2, RoundingMode.HALF_UP).toPlainString());
        String title = texts.getString("inbox.production-order.sent.title").formatted(event.code(), warehouse);
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                title.length() <= InboxMessage.TITLE_MAX ? title : title.substring(0, InboxMessage.TITLE_MAX),
                texts.getString("inbox.production-order.sent.body").formatted(event.totalCartons(), volume,
                        DAY.format(event.needByDate())),
                "/production/orders/" + event.productionOrderId(), SOURCE_TYPE, event.productionOrderId().toString(),
                "production-order-sent:" + event.productionOrderId());
    }
}
