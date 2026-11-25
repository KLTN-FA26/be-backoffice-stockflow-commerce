package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.SalesFollowUps;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WF-02 CONC-2: an order of a signed contract whose installments on signing are paid, yet still PENDING_PAYMENT because
 * the receipt's event never reached {@code PaymentEventListener} (a lock timeout, a crash), is confirmed here. The money
 * is read once for all of them ({@link PaymentService#installmentStandings}); each order then goes through
 * {@link OrderServiceImpl#installmentsPaid} in its own transaction - the listener's own path, which locks the order and
 * reads the money again, so an order confirmed or a receipt voided meanwhile is left as it is, and running this twice
 * changes nothing.
 *
 * <p>Deliberately narrow: event publications are not resubmitted, since every other listener would then have to be
 * idempotent. Not transactional itself, so one order that fails is logged and the others still go through.</p>
 */
@Component
class InstallmentReconciliation {

    private static final Logger log = LoggerFactory.getLogger(InstallmentReconciliation.class);

    /** More orders waiting on their customers than a business has at once; the oldest first. */
    static final int LIMIT = 2000;

    private final SalesFollowUps followUps;
    private final PaymentService payments;
    private final OrderServiceImpl orders;

    InstallmentReconciliation(SalesFollowUps followUps, PaymentService payments, OrderServiceImpl orders) {
        this.followUps = followUps;
        this.payments = payments;
        this.orders = orders;
    }

    /** Hands every waiting order whose installments on signing are paid to the confirmation; returns how many. */
    public int confirmPaid() {
        List<UUID> waiting = followUps.awaitingOnSigning(LIMIT);
        if (waiting.isEmpty()) {
            return 0;
        }
        if (waiting.size() == LIMIT) {
            log.warn("{} or more orders wait for their installment on signing; only the oldest {} were reconciled",
                    LIMIT, LIMIT);
        }
        Map<UUID, InstallmentStanding> standings = payments.installmentStandings(waiting);
        int handed = 0;
        for (UUID orderId : waiting) {
            InstallmentStanding standing = standings.get(orderId);
            if (standing == null || !standing.opened() || !standing.onSigningPaid()) {
                continue;
            }
            try {
                orders.installmentsPaid(orderId, null);
                handed++;
            } catch (RuntimeException e) {
                log.warn("Order {}: its installments on signing are paid but confirming it failed; the next run tries "
                        + "again", orderId, e);
            }
        }
        if (handed > 0) {
            log.warn("{} order(s) had their installments on signing paid and were still pending payment - confirmed by "
                    + "reconciliation", handed);
        }
        return handed;
    }
}
