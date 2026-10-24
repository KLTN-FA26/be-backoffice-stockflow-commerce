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
}
