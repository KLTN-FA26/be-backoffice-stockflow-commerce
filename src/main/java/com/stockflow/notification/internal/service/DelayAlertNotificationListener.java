package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.DelayAlertOpened;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Tells the people a delay alert concerns that it opened (4A-7, kltn-docs 17 §4.12 steps 48-49, BR-30; 00 §6): an
 * in-app message, nothing by e-mail. Who is told is decided by permission, not by role name (cleanup P8), so it follows
 * the role matrix as it is edited:
 * <ul>
 *   <li>AT_RISK, PRODUCTION_LATE, PO_NEEDED - those who follow production: holders of {@value #PRODUCTION_AUDIENCE}
 *       whose scope is the whole business (the Production Coordinator, Sales, the Sales Manager). The warehouse
 *       manager reads the schedule of their warehouse but is not told: BR-30 does not name them.</li>
 *   <li>DELIVERY_LATE - those who deliver: holders of {@value #DELIVERY_AUDIENCE} who can see the order's shipping
 *       warehouse (Sales, the Sales Manager, that warehouse's manager), not the coordinator (BR-30).</li>
 * </ul>
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
    /** Who delivers: the readers of an order's delay flags (DelayAlertController). */
    static final String DELIVERY_AUDIENCE = "delay-alerts:READ";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;

    DelayAlertNotificationListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(DelayAlertOpened event) {
        boolean delivery = "DELIVERY_LATE".equals(event.type());
        // A production alert goes to the business-wide holders only (null: scope ALL); DELIVERY_LATE to those who can
        // see the shipping warehouse - or, while the order has none, the business-wide ones.
        List<StaffContact> recipients = delivery ? staff.activeHolders(DELIVERY_AUDIENCE, event.warehouseId())
                : staff.activeHolders(PRODUCTION_AUDIENCE, null);
        if (recipients.isEmpty()) {
            log.warn("Delay alert {} ({}) of order {} has nobody to tell", event.alertId(), LogSafe.text(event.type()),
                    LogSafe.text(event.orderNumber()));
            return;
        }
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event));
        log.info("Delay alert {} ({}) of order {}: {} recipient(s), {} newly told", event.alertId(),
                LogSafe.text(event.type()), LogSafe.text(event.orderNumber()), recipients.size(), told);
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
