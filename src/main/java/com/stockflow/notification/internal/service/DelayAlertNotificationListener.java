package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.DelayAlertOpened;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the people a delay alert concerns that it opened (4A-7, kltn-docs 17 §4.12 steps 48-49, BR-30; 00 §6): an
 * in-app message, nothing by e-mail. Who is told is decided by permission, not by role name (cleanup P8), so it follows
 * the role matrix as it is edited:
 * <ul>
 *   <li>AT_RISK, PRODUCTION_LATE, PO_NEEDED - those who follow production: holders of {@value #PRODUCTION_AUDIENCE}
 *       whose scope is the whole business (the Production Coordinator, the Sales Manager). The warehouse manager reads
 *       the schedule of their warehouse but is not told: BR-30 does not name them.</li>
 *   <li>DELIVERY_LATE - those who deliver: holders of {@value #DELIVERY_AUDIENCE} who can see the order's shipping
 *       warehouse (the Sales Manager, that warehouse's manager), not the coordinator (BR-30).</li>
 * </ul>
 * Of Sales, only whoever looks after the order's customer (17 BR-30 as changed on 2026-11-28, 00 §6;
 * {@link SalesNotices}): its owner or the owner's cover, else the Sales Managers with the notice marked "cần phân
 * công" - the other salespeople who hold the permission are no longer told.
 *
 * <p>One message per person per alert ({@code delay-alert:<alert id>}): a redelivered event tells nobody twice, and the
 * same condition holding again after it cleared is a new alert, told again. System text only - codes, numbers and
 * days. Titles and bodies are never logged.</p>
 */
@Component
class DelayAlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DelayAlertNotificationListener.class);

    static final String CATEGORY = "DELAY_ALERT";
    static final String SOURCE_TYPE = "DELAY_ALERT";
    /** Who follows production: the production schedule's readers (ProductionScheduleController). */
    static final String PRODUCTION_AUDIENCE = "production-schedule:READ";
    /** Who delivers: those who also see DELIVERY_LATE in the alert list (DelayAlertController, V20261209000400). */
    static final String DELIVERY_AUDIENCE = "delivery-delay-alerts:READ";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StaffDirectory staff;
    private final SalesNotices sales;
    private final Inbox inbox;

    DelayAlertNotificationListener(StaffDirectory staff, SalesNotices sales, Inbox inbox) {
        this.staff = staff;
        this.sales = sales;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(DelayAlertOpened event) {
        boolean delivery = "DELIVERY_LATE".equals(event.type());
        // A production alert goes to the business-wide holders only (null: scope ALL); DELIVERY_LATE to those who can
        // see the shipping warehouse - or, while the order has none, the business-wide ones.
        List<StaffContact> holders = delivery ? staff.activeHolders(DELIVERY_AUDIENCE, event.warehouseId())
                : staff.activeHolders(PRODUCTION_AUDIENCE, null);
        SalesAudience audience = sales.audiences().ofOrder(event.salesOrderId())
                .orElse(new SalesAudience(List.of(), false));
        List<UUID> salespeople = audience.userIds();
        List<UUID> others = sales.exceptOtherSales(holders, new SalesAudience(List.of(), false)).stream()
                .filter(id -> !salespeople.contains(id)).toList();
        if (others.isEmpty() && salespeople.isEmpty()) {
            log.warn("Delay alert {} ({}) of order {} has nobody to tell", event.alertId(), LogSafe.text(event.type()),
                    LogSafe.text(event.orderNumber()));
            return;
        }
        InboxMessage message = message(event);
        int told = inbox.post(others, message)
                + inbox.post(salespeople, SalesNotices.marked(message, audience.needsAssignment()));
        log.info("Delay alert {} ({}) of order {}: {} recipient(s), {} newly told", event.alertId(),
                LogSafe.text(event.type()), LogSafe.text(event.orderNumber()), others.size() + salespeople.size(),
                told);
    }

    static InboxMessage message(DelayAlertOpened event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String key = "inbox.delay.alert." + event.type();
        String title;
        String body;
        String link;
        switch (event.type()) {
            case "AT_RISK" -> {
                title = texts.getString(key + ".title").formatted(event.productionOrderCode(),
                        day(event.expectedCompletionDate()), day(event.dueDate()));
                body = texts.getString(key + ".body").formatted(event.productionOrderCode(), event.orderNumber(),
                        day(event.expectedCompletionDate()), day(event.dueDate()));
                link = "/production/orders/" + event.productionOrderId();
            }
            case "PRODUCTION_LATE" -> {
                title = texts.getString(key + ".title").formatted(event.productionOrderCode(), day(event.dueDate()));
                body = texts.getString(key + ".body").formatted(event.productionOrderCode(), event.orderNumber(),
                        day(event.dueDate()));
                link = "/production/orders/" + event.productionOrderId();
            }
            case "PO_NEEDED" -> {
                title = texts.getString(key + ".title").formatted(event.orderNumber(), event.batchSeq());
                body = texts.getString(key + ".body").formatted(event.batchSeq(), event.orderNumber(),
                        day(event.dueDate()));
                link = "/production/schedule";
            }
            case "DELIVERY_LATE" -> {
                title = texts.getString(key + ".title").formatted(event.orderNumber(), event.batchSeq());
                body = texts.getString(key + ".body").formatted(event.batchSeq(), event.orderNumber(),
                        day(event.dueDate()));
                link = "/admin/orders/" + event.salesOrderId();
            }
            default -> throw new IllegalArgumentException("Unknown delay alert type " + event.type());
        }
        return new InboxMessage(CATEGORY, InboxSeverity.WARN, title, body, link, SOURCE_TYPE,
                event.alertId().toString(), "delay-alert:" + event.alertId());
    }

    private static String day(LocalDate day) {
        return day == null ? "-" : DAY.format(day);
    }
}
