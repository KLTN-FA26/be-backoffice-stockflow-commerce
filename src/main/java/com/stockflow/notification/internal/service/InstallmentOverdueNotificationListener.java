package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.InstallmentOverdue;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells Sales that a customer's installment turned overdue (kltn-docs 15 §4.3 step 11: "khách phát sinh quá hạn sau
 * khi đơn đã xác nhận - hệ thống cảnh báo Sale; Sale có thể treo tay các đơn đang chạy của khách", 17 §4.9;
 * WF4-OVERDUE-TRIGGER, WF4-OVERDUE-SALE): an in-app message, nothing by e-mail. Who is told is whoever looks after the
 * customer today (15 §4.1 step 4 and BR-22 as changed on 2026-11-28, 00 §6; {@link SalesNotices}): its owner or the
 * owner's cover, else the Sales Managers with the notice marked "cần phân công" - no longer every holder of
 * {@value #AUDIENCE}, the permission to hold an order by hand (any of them still may: 17 BR-32, recorded "làm hộ").
 *
 * <p>One message per person per installment and due date ({@code installment-overdue:<installment id>:<due date>},
 * WF-05 BE-49): a restart replays events, and the scan may publish an installment again, without anybody told twice;
 * but an installment given a new due date - its delivery corrected, its milestone moved - and overdue again on it is
 * told again, which a key of the installment alone never did. System text only - numbers, dates and the amount. Titles
 * and bodies are never logged.</p>
 */
@Component
class InstallmentOverdueNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(InstallmentOverdueNotificationListener.class);

    static final String CATEGORY = "PAYMENT_OVERDUE";
    static final String SOURCE_TYPE = "INSTALLMENT";
    /** Who decides to hold the customer's running orders by hand (OrderReleaseController#hold). */
    static final String AUDIENCE = "sales-order-holds:UPDATE";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale VI = Locale.forLanguageTag("vi");

    private final SalesNotices sales;
    private final Inbox inbox;

    InstallmentOverdueNotificationListener(SalesNotices sales, Inbox inbox) {
        this.sales = sales;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(InstallmentOverdue event) {
        SalesAudience audience = (event.customerId() != null ? sales.audiences().ofCustomer(event.customerId())
                : sales.audiences().ofOrder(event.orderId())).orElse(null);
        if (audience == null || audience.userIds().isEmpty()) {
            log.warn("Installment {} of contract {} is overdue; nobody looks after the customer and no manager is "
                    + "active to be told", event.installmentId(), LogSafe.text(event.contractNumber()));
            return;
        }
        int told = inbox.post(audience.userIds(), SalesNotices.marked(message(event), audience.needsAssignment()));
        log.info("Installment {} of contract {} overdue: {} recipient(s), {} newly told", event.installmentId(),
                LogSafe.text(event.contractNumber()), audience.userIds().size(), told);
    }

    static InboxMessage message(InstallmentOverdue event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", VI);
        String title = texts.getString("inbox.installment.overdue.title").formatted(event.contractNumber(),
                event.seq());
        String body = texts.getString("inbox.installment.overdue.body").formatted(event.seq(),
                event.contractNumber(), event.orderNumber(), day(event.dueDate()), amount(event));
        return new InboxMessage(CATEGORY, InboxSeverity.WARN, title, body, "/admin/orders/" + event.orderId(),
                SOURCE_TYPE, event.installmentId().toString(), dedupeKey(event));
    }

    /** Once per installment and due date; an event without a due date (none is published) keys on the installment. */
    static String dedupeKey(InstallmentOverdue event) {
        return "installment-overdue:" + event.installmentId()
                + (event.dueDate() == null ? "" : ":" + event.dueDate());
    }

    private static String day(LocalDate day) {
        return day == null ? "-" : DAY.format(day);
    }

    private static String amount(InstallmentOverdue event) {
        return NumberFormat.getIntegerInstance(VI).format(event.outstanding()) + " " + event.currency();
    }
}
