package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.StockDamageReported;
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
 * Tells the people who act on a damage report (SCRUM-475/277; docs 03 §5), as three separate notices, because three
 * different people have something to do:
 * <ul>
 *   <li>the <b>warehouse managers</b> who can see the report's warehouse decide what happens to the damaged units -
 *       write off, back to the supplier, or restore. Until one of them does, the units sit in quarantine and nobody
 *       is asked (the reporter cannot decide: four eyes);</li>
 *   <li>when nothing replaced an order's damaged units, the <b>order coordinators</b> of that warehouse: the order is
 *       ON_HOLD (INVENTORY) and only their reallocation of the short pick line moves it again (X-SHORT);</li>
 *   <li>when the damage leaves the SKU's checkout reservations promising more than its free stock, the
 *       <b>inventory planners</b>: those orders will not allocate in full at release unless stock arrives first, and
 *       replenishing is the planner's job.</li>
 * </ul>
 *
 * <p>Delivered as every staff notice ({@link StaffNotices}): in-app first, one inbox row per person per report and
 * audience ({@code stock-damage:<report>:<audience>}), then e-mail best effort to the people newly told, so a
 * redelivered event tells nobody twice. System text only - codes and numbers, never the reporter's note.</p>
 */
@Component
class StockDamageNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(StockDamageNotificationListener.class);

    static final String CATEGORY = "STOCK_DAMAGE";
    static final String SOURCE_TYPE = "STOCK_DAMAGE_REPORT";

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    StockDamageNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(StockDamageReported event) {
        tell(Role.WAREHOUSE_MANAGER, event, decision(event));
        if (event.orderOnHold() && event.orderId() != null) {
            tell(Role.ORDER_COORDINATOR, event, orderHeld(event));
        }
        if (event.skuReservationShortfall() > 0) {
            tell(Role.INVENTORY_PLANNER, event, shortfall(event));
        }
    }

    private void tell(Role audience, StockDamageReported event, InboxMessage message) {
        List<StaffContact> recipients = staff.activeStaff(audience, event.warehouseId());
        if (recipients.isEmpty()) {
            log.warn("Damage report {}: no active {} for its warehouse; nobody was notified",
                    LogSafe.text(event.reportNumber()), audience);
            return;
        }
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_stock_damage"));
        log.info("Damage report {}: {} {} account(s), {} newly told", LogSafe.text(event.reportNumber()),
                recipients.size(), audience, told);
    }

    /** The warehouse managers' notice: what was found, where it went, and that it waits for their decision. */
    static InboxMessage decision(StockDamageReported event) {
        ResourceBundle texts = texts();
        String body = texts.getString("inbox.damage.decide.body").formatted(event.quantity(), event.sku(),
                event.lotNumber() == null ? "" : texts.getString("inbox.damage.lot").formatted(event.lotNumber()),
                label(texts, "inbox.damage.reason.", event.reasonCode()),
                label(texts, "inbox.damage.origin.", event.origin()), event.sourceLocationCode(),
                event.damageLocationCode());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN, texts.getString("inbox.damage.decide.title")
                .formatted(event.reportNumber(), event.quantity(), event.sku()), body,
                "/admin/inventory/damage-reports/" + event.reportId(), SOURCE_TYPE, event.reportId().toString(),
                key(event, Role.WAREHOUSE_MANAGER));
    }

    /** The coordinators' notice: the order stopped because nothing replaced its damaged units. */
    static InboxMessage orderHeld(StockDamageReported event) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.CRITICAL, texts.getString("inbox.damage.order.title")
                .formatted(event.reportNumber()), texts.getString("inbox.damage.order.body").formatted(
                event.quantity(), event.sku(), label(texts, "inbox.damage.origin.", event.origin())),
                "/admin/orders/" + event.orderId(), SOURCE_TYPE, event.reportId().toString(),
                key(event, Role.ORDER_COORDINATOR));
    }

    /** The planners' notice: checkout reservations now promise more than the free stock. */
    static InboxMessage shortfall(StockDamageReported event) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.WARN, texts.getString("inbox.damage.shortfall.title")
                .formatted(event.sku(), event.skuReservationShortfall()),
                texts.getString("inbox.damage.shortfall.body").formatted(event.reportNumber(), event.sku(),
                        event.skuReservationShortfall()),
                "/admin/inventory/damage-reports/" + event.reportId(), SOURCE_TYPE, event.reportId().toString(),
                // One a day per SKU and warehouse, however many small reports: the shortfall is the SKU's.
                "stock-damage-shortfall:%s:%s:%s".formatted(event.sku(), event.warehouseId(),
                        com.stockflow.common.domain.BusinessCalendar.date(java.time.Instant.now())));
    }

    private static String key(StockDamageReported event, Role audience) {
        return "stock-damage:%s:%s".formatted(event.reportId(), audience.name());
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }

    /** The label of a code, or the code itself for one the bundle does not know yet. */
    private static String label(ResourceBundle texts, String prefix, String code) {
        return code != null && texts.containsKey(prefix + code) ? texts.getString(prefix + code) : String.valueOf(code);
    }
}
