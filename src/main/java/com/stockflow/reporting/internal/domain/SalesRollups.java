package com.stockflow.reporting.internal.domain;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The arithmetic of the sales rollups, and the only place that decides when an event changes them.
 *
 * <p>Revenue is the orders placed, less the ones cancelled (decision DASHHOLD: an ON_HOLD order is counted). A
 * cancellation takes the order out on the day it was PLACED, with the amounts the placement added. Both calls are
 * idempotent, so an event the registry delivers twice, or the rebuild running over the same orders, adds nothing
 * the second time.</p>
 *
 * <p>The two events can reach reporting in either order - each is handled in its own asynchronous transaction.
 * {@link #cancel} therefore records the placement too when it was not seen yet: the order is then counted in neither,
 * and the placement that arrives afterwards finds it recorded and does nothing.</p>
 *
 * <p>The rebuild replays orders through these same two methods, which is why it gives the same numbers as the
 * listeners - then applies again what returns and short completions took off ({@link #closed}), which it keeps.</p>
 */
public final class SalesRollups {

    private final SalesRollupLedger ledger;

    public SalesRollups(SalesRollupLedger ledger) {
        this.ledger = ledger;
    }

    /** The day the order was placed on, as its placement was recorded; empty when it was not (yet). */
    public java.util.Optional<java.time.LocalDate> placedDay(UUID orderId) {
        return ledger.placedContribution(orderId).map(OrderContribution::day);
    }

    /** @return false if this order's placement was already applied */
    public boolean place(OrderContribution order) {
        if (!ledger.markApplied(order.orderId(), OrderEventType.PLACED, order)) {
            return false;
        }
        ledger.apply(order, 1);
        return true;
    }

    /**
     * A return gave money back for goods of an order (SCRUM-260): its credited value and units come off the revenue of
     * the day that order was placed, as a cancellation's do - so a day's revenue is what was sold that day, less what was
     * cancelled or credited back for it. Free replacement goods take nothing off: the goods were sold once.
     *
     * @return false if this return's {@code event} was applied before, or it credited nothing
     */
    public boolean returned(UUID returnRequestId, String event, OrderContribution taken) {
        if (taken.total().signum() <= 0 || !ledger.markReturnApplied(returnRequestId, event, taken)) {
            return false;
        }
        ledger.applyReturn(taken);
        return true;
    }

    /**
     * Applies the closures a day's rebuild kept (see {@link SalesRollupLedger#closuresOf}) after replaying its orders:
     * the units in-house production completed short (S6) while it existed. No closure is recorded any more - production
     * left the system - but those recorded before still lowered what their orders were worth.
     */
    public void reapplyClosures(java.time.LocalDate day) {
        for (OrderContribution taken : ledger.closuresOf(day)) {
            ledger.applyReturn(taken);
        }
    }

    /**
     * @param orderIfNotPlacedYet what the order would have contributed, asked for only when the placement was not
     *                            applied yet; null when the order cannot be read, in which case nothing is done
     * @return false if this order's cancellation was already applied, or there was nothing to apply it to
     */
    public boolean cancel(UUID orderId, Supplier<OrderContribution> orderIfNotPlacedYet) {
        OrderContribution known = ledger.placedContribution(orderId).orElseGet(orderIfNotPlacedYet);
        if (known == null) {
            return false;
        }
        if (!ledger.markApplied(orderId, OrderEventType.CANCELLED, known)) {
            return false;
        }
        // Already applied: take it out again. Not yet: record it so that it never counts - net zero.
        if (!ledger.markApplied(orderId, OrderEventType.PLACED, known)) {
            // What is left of it: closures applied before took their part off already.
            ledger.apply(withoutClosures(ledger.placedContribution(orderId).orElseThrow(),
                    ledger.closuresOfOrder(orderId)), -1);
        }
        return true;
    }

    /**
     * The placement less the closures taken off it: the total, and per SKU the units and revenue. A SKU left with
     * nothing is dropped.
     */
    static OrderContribution withoutClosures(OrderContribution placed, java.util.List<OrderContribution> closures) {
        if (closures.isEmpty()) {
            return placed;
        }
        java.util.Map<String, int[]> units = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.math.BigDecimal> revenue = new java.util.LinkedHashMap<>();
        java.math.BigDecimal total = placed.total();
        for (OrderContribution.Line line : placed.lines()) {
            units.computeIfAbsent(line.sku(), sku -> new int[1])[0] += line.quantity();
            revenue.merge(line.sku(), line.revenue(), java.math.BigDecimal::add);
        }
        for (OrderContribution closure : closures) {
            total = total.subtract(closure.total());
            for (OrderContribution.Line line : closure.lines()) {
                units.computeIfAbsent(line.sku(), sku -> new int[1])[0] -= line.quantity();
                revenue.merge(line.sku(), line.revenue().negate(), java.math.BigDecimal::add);
            }
        }
        java.util.List<OrderContribution.Line> left = new java.util.ArrayList<>();
        units.forEach((sku, quantity) -> {
            java.math.BigDecimal value = revenue.get(sku);
            if (quantity[0] != 0 || value.signum() != 0) {
                left.add(new OrderContribution.Line(sku, quantity[0], value));
            }
        });
        return new OrderContribution(placed.orderId(), placed.day(), placed.currency(), total, left);
    }
}
