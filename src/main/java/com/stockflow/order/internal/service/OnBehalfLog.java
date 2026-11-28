package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleKind;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.order.internal.domain.OnBehalfAction;
import com.stockflow.order.internal.repository.OrderOnBehalfActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * "Làm hộ" (kltn-docs 17 BR-32; 14 §4.4 step 18; 15 BR-22): strict mode never blocks the work after signing, so that
 * an order does not wait while its salesperson is away - but when the person doing it is neither the customer's owner
 * nor the owner's cover of the day, the order's history says so ({@code ordering.order_on_behalf_action}, listed as
 * ON_BEHALF) and the one who looks after the customer is told in-app (00 §6, INFO).
 *
 * <p>Who looks after the customer is {@link ResponsibleSales}'s answer, the one rule every notice follows. A customer
 * with no active owner has nobody to tell: the entry is still written, naming who did it (17 BR-32). Run in the
 * action's own transaction ({@link OrderOperations}), after it succeeded: a refused action leaves no entry, and the
 * entry and the notice commit with the action or not at all. The notice is system text - the action and the order
 * number - and its link opens the order, whose history names who acted.</p>
 */
@Component
class OnBehalfLog {

    private static final Logger log = LoggerFactory.getLogger(OnBehalfLog.class);

    static final String CATEGORY = "ORDER_ON_BEHALF";

    private final OrderOnBehalfActions entries;
    private final ResponsibleSales responsible;
    private final Inbox inbox;
    private final Clock clock;

    OnBehalfLog(OrderOnBehalfActions entries, ResponsibleSales responsible, Inbox inbox, Clock clock) {
        this.entries = entries;
        this.responsible = responsible;
        this.inbox = inbox;
        this.clock = clock;
    }

    /**
     * Records {@code action} on the order when {@code actorId} did it for someone else, and tells that someone.
     *
     * @return whether an entry was written: false for the owner or the cover acting, for no actor (the system), and for
     *         an order or customer that is not there
     */
    public boolean record(UUID orderId, UUID actorId, OnBehalfAction action) {
        if (orderId == null || actorId == null) {
            return false;
        }
        OrderOnBehalfActions.OrderFacts order = entries.orderFacts(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        Instant now = clock.instant();
        Optional<Responsible> found = responsible.responsibleFor(order.customerId(), BusinessCalendar.date(now));
        if (found.isEmpty()) {
            return false;
        }
        Responsible who = found.get();
        if (who.kind() != ResponsibleKind.MANAGERS
                && (actorId.equals(who.ownerId()) || actorId.equals(who.actingUserId()))) {
            return false;   // the owner, or the cover of the day: their own work
        }
        UUID actingFor = who.actingUserId();
        UUID entry = entries.record(orderId, action.name(), actorId, actingFor, now);
        if (actingFor != null) {
            inbox.post(List.of(actingFor), message(orderId, order.orderNumber(), action, entry));
        }
        log.info("Order {}: {} done by {} for {}", order.orderNumber(), action, actorId,
                actingFor == null ? "a customer with no active owner" : actingFor);
        return true;
    }

    static InboxMessage message(UUID orderId, String orderNumber, OnBehalfAction action, UUID entryId) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String what = texts.getString("label.OnBehalfAction." + action.name());
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                texts.getString("inbox.order.onBehalf.title").formatted(orderNumber),
                texts.getString("inbox.order.onBehalf.body").formatted(what, orderNumber),
                "/admin/orders/" + orderId, "ORDER", orderId.toString(), "order-on-behalf:" + entryId);
    }
}
