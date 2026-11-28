package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ShortageCardChanged;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import com.stockflow.identity.api.StaffContact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * The in-app notices of the shortage choice card (kltn-docs 17 §4.14 steps 57-58, 00 §6), each a link to the order's
 * "Thiếu thùng" section:
 * <ul>
 *   <li>the customer chose ({@code CHOSEN}) - whoever looks after the customer: the customer's owner or the owner's cover,
 *       else the Sales Managers marked "cần phân công" ({@link SalesNotices}, sales-owner plan §2.2), never every
 *       salesperson;</li>
 *   <li>Sales carried the choice out ({@code EXECUTED} for wait, reduce and cancel; {@code EXECUTION_STARTED} for "ship
 *       part") - the warehouse managers of the order's shipping warehouse, chosen by permission as for the other notices
 *       "Quản lý kho của kho xuất" ({@value #WAREHOUSE_AUDIENCE}, {@link PaymentDueAtPackingNotificationListener}); for
 *       wait also those who draw up production orders ({@value #PRODUCTION_AUDIENCE}, as the damaged write-off notice),
 *       to make the batch's missing cartons;</li>
 *   <li>the partial check of a "ship part" is done ({@code CLOSE_REST_DUE}) - whoever looks after the customer, to close
 *       the rest.</li>
 * </ul>
 * Sending a card is the customer's notice (the chat's, like any card); a card ending, replaced or carried out as "ship
 * part" at the closure, tells nobody here.
 *
 * <p>One message per person per card and change ({@code shortage:<card id>:<change>}): a redelivered event tells nobody
 * twice. System text only - the order's number and the option. Titles and bodies are never logged.</p>
 */
@Component
class ShortageCardNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ShortageCardNotificationListener.class);

    static final String CATEGORY = "SHORTAGE_CARD";
    static final String SOURCE_TYPE = "ORDER";
    static final String CHOSEN = "CHOSEN";
    static final String EXECUTED = "EXECUTED";
    static final String EXECUTION_STARTED = "EXECUTION_STARTED";
    static final String CLOSE_REST_DUE = "CLOSE_REST_DUE";
    /** Who decides the exceptions of the carton check at a warehouse: its manager (08 BR-06). */
    static final String WAREHOUSE_AUDIENCE = "fulfillment-packing:APPROVE";
    /** Who draws up production orders (17 §4.3 step 6). */
    static final String PRODUCTION_AUDIENCE = "production-orders:CREATE";
    private static final String WAIT = "WAIT_FOR_PRODUCTION";
    private static final String SHIP_PART = "SHIP_PART_CLOSE_REST";

    private final SalesNotices sales;
    private final StaffDirectory staff;
    private final Inbox inbox;

    ShortageCardNotificationListener(SalesNotices sales, StaffDirectory staff, Inbox inbox) {
        this.sales = sales;
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(ShortageCardChanged event) {
        if (event.chosenOption() == null) {
            return;
        }
        switch (event.change()) {
            case CHOSEN -> chosen(event);
            case EXECUTED -> {
                if (!SHIP_PART.equals(event.chosenOption())) {
                    carriedOut(event);
                }
            }
            case EXECUTION_STARTED -> carriedOut(event);
            case CLOSE_REST_DUE -> closeRestDue(event);
            default -> { }
        }
    }

    /** Sales carried the choice out: the shipping warehouse's managers, and for a wait the production coordinators. */
    private void carriedOut(ShortageCardChanged event) {
        int told = 0;
        if (WAIT.equals(event.chosenOption())) {
            told += inbox.post(holders(PRODUCTION_AUDIENCE, event.shippingWarehouseId()),
                    executed(event, "inbox.shortage.executed.production.body"));
        }
        if (event.shippingWarehouseId() != null) {
            told += inbox.post(holders(WAREHOUSE_AUDIENCE, event.shippingWarehouseId()),
                    executed(event, "inbox.shortage.executed.body"));
        }
        log.info("Shortage card {} carried out ({}): {} newly told", event.cardId(), event.chosenOption(), told);
    }

    private void closeRestDue(ShortageCardChanged event) {
        SalesAudience audience = sales.audiences().ofOrder(event.orderId()).orElse(new SalesAudience(List.of(), false));
        if (audience.userIds().isEmpty()) {
            log.warn("Shortage card {}: the rest is due to be closed and nobody looks after the customer",
                    event.cardId());
            return;
        }
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String number = LogSafe.text(event.orderNumber());
        InboxMessage message = new InboxMessage(CATEGORY, InboxSeverity.INFO,
                cut(texts.getString("inbox.shortage.closeRestDue.title").formatted(number), InboxMessage.TITLE_MAX),
                cut(texts.getString("inbox.shortage.closeRestDue.body").formatted(number), InboxMessage.BODY_MAX),
                link(event), SOURCE_TYPE, event.orderId().toString(), dedupe(event));
        int told = inbox.post(audience.userIds(), SalesNotices.marked(message, audience.needsAssignment()));
        log.info("Shortage card {}: close the rest now - {} newly told", event.cardId(), told);
    }

    private List<UUID> holders(String permission, UUID warehouseId) {
        return staff.activeHolders(permission, warehouseId).stream().map(StaffContact::userId).filter(Objects::nonNull)
                .distinct().toList();
    }

    static InboxMessage executed(ShortageCardChanged event, String bodyKey) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String number = LogSafe.text(event.orderNumber());
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                cut(texts.getString("inbox.shortage.executed.title").formatted(number), InboxMessage.TITLE_MAX),
                cut(texts.getString(bodyKey).formatted(number, event.missingCartons(), option(texts, event)),
                        InboxMessage.BODY_MAX),
                link(event), SOURCE_TYPE, event.orderId().toString(), dedupe(event));
    }

    private static String option(ResourceBundle texts, ShortageCardChanged event) {
        String key = "label.ShortageOption." + event.chosenOption();
        return texts.containsKey(key) ? texts.getString(key) : LogSafe.text(event.chosenOption());
    }

    private static String link(ShortageCardChanged event) {
        return "/admin/orders/" + event.orderId() + "#shortage";
    }

    private static String dedupe(ShortageCardChanged event) {
        return "shortage:" + event.cardId() + ":" + event.change();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private void chosen(ShortageCardChanged event) {
        SalesAudience audience = sales.audiences().ofOrder(event.orderId()).orElse(new SalesAudience(List.of(), false));
        if (audience.userIds().isEmpty()) {
            log.warn("Shortage card {}: nobody looks after the customer and no manager is active; nobody was told",
                    event.cardId());
            return;
        }
        int told = inbox.post(audience.userIds(), SalesNotices.marked(message(event), audience.needsAssignment()));
        log.info("Shortage card {} chosen: {} person(s), {} newly told", event.cardId(), audience.userIds().size(), told);
    }

    static InboxMessage message(ShortageCardChanged event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String number = LogSafe.text(event.orderNumber());
        String option = option(texts, event);
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                texts.getString("inbox.shortage.chosen.title").formatted(number),
                texts.getString("inbox.shortage.chosen.body").formatted(number, event.missingCartons(), option),
                "/admin/orders/" + event.orderId() + "#shortage", SOURCE_TYPE, event.orderId().toString(),
                "shortage:" + event.cardId() + ":" + CHOSEN);
    }
}
