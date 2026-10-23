package com.stockflow.reporting.internal.service;

import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.reporting.internal.domain.BusinessDay;
import com.stockflow.reporting.internal.domain.OrderContribution;
import com.stockflow.reporting.internal.domain.SalesRollupLedger;
import com.stockflow.reporting.internal.domain.SalesRollups;
import com.stockflow.reporting.internal.repository.SalesRollupLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Applies one order event to the sales rollups: the use case behind {@link SalesRollupListener}.
 *
 * <p>What an order contributes is read from the order module (its {@code api}), not taken from the event, for two
 * reasons: the event carries neither the placement time nor the line totals, and the rebuild has no event at all - it
 * must compute the same numbers from the same source. Both run through {@link SalesRollups}.</p>
 *
 * <p>Each method is one transaction (joining the listener's) holding the shared rollup lock, so a rebuild of a day
 * never interleaves with an order being applied to it.</p>
 */
@Service
class SalesRollupService {

    private static final Logger LOG = LoggerFactory.getLogger(SalesRollupService.class);

    /** Single currency (owner decision). An order in another one is left out rather than added to the wrong total. */
    static final String CURRENCY = "VND";

    private final OrderService orders;
    private final SalesRollups rollups;
    private final SalesRollupLock lock;

    SalesRollupService(OrderService orders, SalesRollupLedger ledger, SalesRollupLock lock) {
        this.orders = orders;
        this.rollups = new SalesRollups(ledger);
        this.lock = lock;
    }

    /** {@code OrderPlaced}: +1 order, +total, and each line's units and revenue, on the day it was placed. */
    @Transactional
    public void orderPlaced(UUID orderId) {
        lock.shared();
        Optional<OrderContribution> order = orders.findById(orderId).flatMap(SalesRollupService::contributionOf);
        if (order.isEmpty()) {
            LOG.warn("Sales rollup: order {} is not countable (unknown, DRAFT, or not in {}); OrderPlaced ignored",
                    orderId, CURRENCY);
            return;
        }
        if (!rollups.place(order.get())) {
            LOG.debug("Sales rollup: placement of order {} was applied before", orderId);
        }
    }

    /** {@code OrderCancelled}: the same amounts taken out again, on the day the order was PLACED. */
    @Transactional
    public void orderCancelled(UUID orderId) {
        lock.shared();
        boolean applied = rollups.cancel(orderId,
                () -> orders.findById(orderId).flatMap(SalesRollupService::contributionOf).orElse(null));
        if (!applied) {
            LOG.debug("Sales rollup: cancellation of order {} was applied before, or the order is not countable", orderId);
        }
    }

    /**
     * What an order adds to the rollups: empty for a DRAFT (never counted: a draft is not a sale) and for an order in
     * another currency than {@link #CURRENCY}.
     */
    static Optional<OrderContribution> contributionOf(OrderSummary order) {
        if (order.status() == OrderStatus.DRAFT) {
            return Optional.empty();
        }
        String currency = order.total().currency().getCurrencyCode();
        if (!CURRENCY.equals(currency)) {
            LOG.warn("Sales rollup: order {} is in {}, not {}; left out", order.orderId(), currency, CURRENCY);
            return Optional.empty();
        }
        return Optional.of(new OrderContribution(order.orderId(), BusinessDay.of(order.placedAt()), currency,
                order.total().amount(),
                order.lines().stream()
                        .map(line -> new OrderContribution.Line(line.sku(), line.quantity(), line.lineTotal().amount()))
                        .toList()));
    }
}
