package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.api.SalesOwnership;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The order module's two questions about a customer's Sales owner (sales-owner plan, kltn-docs 14 §4.4), asked of
 * {@code customer :: api} so that every quote and contract step asks them the same way:
 * <ul>
 *   <li><b>may this person draw up, change, send or cancel a quote or contract for this customer</b> (strict mode,
 *       14 BR-25, plan BR-S12): the owner, the owner's cover of the day, a manager, or anyone while the customer has
 *       no active owner. Never claims the customer (owner decision D43: drawing up a quote makes nobody an owner);</li>
 *   <li><b>who owns the customer now</b>, to record on a document drawn up (14 BR-26).</li>
 * </ul>
 * Called inside the caller's transaction, after the caller has made sure the customer exists:
 * {@link SalesOwnership#mayActFor} answers false for an unknown customer.
 */
@Component
class CustomerOwnership {

    private final SalesOwnership ownership;
    private final Clock clock;

    CustomerOwnership(SalesOwnership ownership, Clock clock) {
        this.ownership = ownership;
        this.clock = clock;
    }

    /**
     * 14 BR-25: refuses a person who may not act for the customer.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_OWNED_BY_OTHER_SALES} (409), naming the owner
     */
    void requireMayActFor(CurrentUser user, UUID customerId) {
        ownership.requireMayActFor(user.userId(), customerId, today());
    }

    /**
     * 14 BR-26: the customer's owner to record on a document drawn up now; null when it has none, and null when its
     * owner left (NEEDS_ASSIGNMENT): the one who left does not look after it any more.
     */
    UUID ownerAtDrawingUp(UUID customerId) {
        return ownership.ownerOf(customerId).filter(owner -> owner.state() == SalesOwnerState.ASSIGNED)
                .map(CustomerSalesOwner::salesOwnerId).orElse(null);
    }

    LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }
}
