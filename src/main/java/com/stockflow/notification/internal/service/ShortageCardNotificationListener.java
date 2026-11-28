package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ShortageCardChanged;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The customer chose a way on the shortage choice card (kltn-docs 17 §4.14 step 57, 00 §6): whoever looks after the
 * customer hears of it - an in-app message with a link to the order's "Thiếu thùng" section, where Sales carry the choice
 * out. That is the customer's owner or the owner's cover, else the Sales Managers marked "cần phân công"
 * ({@link SalesNotices}, sales-owner plan §2.2) - never every salesperson.
 *
 * <p>The other changes of a card tell nobody here: sending it is the customer's notice (the chat's, like any card),
 * and a card ending is the Sales' own doing or nothing left to decide. The notices of carrying a choice out (the
 * warehouse manager, the production coordinator, "close the rest now") belong to the execution.</p>
 *
 * <p>One message per person per card ({@code shortage:<card id>:CHOSEN}): a redelivered event tells nobody twice. System
 * text only - the order's number and the option. Titles and bodies are never logged.</p>
 */
@Component
class ShortageCardNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ShortageCardNotificationListener.class);

    static final String CATEGORY = "SHORTAGE_CARD";
    static final String SOURCE_TYPE = "ORDER";
    static final String CHOSEN = "CHOSEN";

    private final SalesNotices sales;
    private final Inbox inbox;

    ShortageCardNotificationListener(SalesNotices sales, Inbox inbox) {
        this.sales = sales;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(ShortageCardChanged event) {
        if (!CHOSEN.equals(event.change()) || event.chosenOption() == null) {
            return;
        }
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
        String key = "label.ShortageOption." + event.chosenOption();
        String option = texts.containsKey(key) ? texts.getString(key) : LogSafe.text(event.chosenOption());
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                texts.getString("inbox.shortage.chosen.title").formatted(number),
                texts.getString("inbox.shortage.chosen.body").formatted(number, event.missingCartons(), option),
                "/admin/orders/" + event.orderId() + "#shortage", SOURCE_TYPE, event.orderId().toString(),
                "shortage:" + event.cardId() + ":" + CHOSEN);
    }
}
