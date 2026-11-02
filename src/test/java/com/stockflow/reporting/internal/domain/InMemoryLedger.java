package com.stockflow.reporting.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The ledger port over maps: the same contract as the SQL adapter, for testing {@link SalesRollups} without a database. */
final class InMemoryLedger implements SalesRollupLedger {

    record Key(UUID orderId, OrderEventType type) {
    }

    record Day(int orders, BigDecimal revenue) {
    }

    record Product(int quantity, BigDecimal revenue) {
    }

    final Map<Key, OrderContribution> applied = new HashMap<>();
    final Map<LocalDate, Day> days = new HashMap<>();
    final Map<String, Product> products = new HashMap<>();

    @Override
    public boolean markApplied(UUID orderId, OrderEventType type, OrderContribution contribution) {
        return applied.putIfAbsent(new Key(orderId, type), contribution) == null;
    }

    @Override
    public Optional<OrderContribution> placedContribution(UUID orderId) {
        return Optional.ofNullable(applied.get(new Key(orderId, OrderEventType.PLACED)));
    }

    @Override
    public void apply(OrderContribution c, int sign) {
        days.merge(c.day(), new Day(sign, c.total().multiply(BigDecimal.valueOf(sign))),
                (a, b) -> new Day(a.orders() + b.orders(), a.revenue().add(b.revenue())));
        for (OrderContribution.Line line : c.lines()) {
            products.merge(line.sku() + "@" + c.day(),
                    new Product(line.quantity() * sign, line.revenue().multiply(BigDecimal.valueOf(sign))),
                    (a, b) -> new Product(a.quantity() + b.quantity(), a.revenue().add(b.revenue())));
        }
    }

    @Override
    public void clearDay(LocalDate day) {
        days.remove(day);
        products.keySet().removeIf(key -> key.endsWith("@" + day));
        applied.values().removeIf(c -> c.day().equals(day));
    }

    record ReturnKey(UUID returnRequestId, String event) {
    }

    final Map<ReturnKey, OrderContribution> returns = new java.util.LinkedHashMap<>();

    @Override
    public boolean markReturnApplied(UUID returnRequestId, String event, OrderContribution taken) {
        return returns.putIfAbsent(new ReturnKey(returnRequestId, event), taken) == null;
    }

    @Override
    public void applyReturn(OrderContribution taken) {
        Day day = days.get(taken.day());
        if (day == null) {
            throw new IllegalStateException("No day " + taken.day());
        }
        days.put(taken.day(), new Day(day.orders(), day.revenue().subtract(taken.total())));
        for (OrderContribution.Line line : taken.lines()) {
            products.merge(line.sku() + "@" + taken.day(), new Product(-line.quantity(), line.revenue().negate()),
                    (a, b) -> new Product(a.quantity() + b.quantity(), a.revenue().add(b.revenue())));
        }
    }

    @Override
    public java.util.List<OrderContribution> returnsOf(LocalDate day) {
        return returns.values().stream().filter(c -> c.day().equals(day)).toList();
    }
}
