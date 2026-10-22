package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.LotTrace;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read side of the lot trace (SCRUM-153): plain SQL over {@code stock_lot}, {@code stock_item},
 * {@code stock_reservation} and {@code stock_movement}, all in this module's schema.
 *
 * <p>{@code warehouses} are warehouse prefixes — the first part of every location code — and narrow every figure
 * to locations inside them; empty means every warehouse. A lot "was in" a warehouse when a stock row of it sits
 * there or a ledger line of it names a location there.</p>
 */
public interface LotTraceSearch {

    /**
     * @param from inclusive; with {@code to}, a lot must have a ledger line in this window (inside {@code warehouses})
     * @param to    exclusive
     * @param today the business date: stock past its expiry on this day is on hand but not available
     */
    record Filter(String sku, String lotNumber, Set<String> warehouses, Instant from, Instant to,
                  java.time.LocalDate today) {
        public Filter {
            warehouses = warehouses == null ? Set.of() : Set.copyOf(warehouses);
            java.util.Objects.requireNonNull(today, "today");
        }
    }

    long countLots(Filter filter);

    List<LotTrace.Summary> lots(Filter filter, long offset, int limit);

    /** The lot, when it exists and — with {@code warehouses} — was in one of them; availability as of {@code today}. */
    Optional<LotTrace.Summary> lot(UUID lotId, Set<String> warehouses, java.time.LocalDate today);

    List<LotTrace.Balance> balances(UUID lotId, Set<String> warehouses);

    List<LotTrace.Hold> holds(UUID lotId, Set<String> warehouses);

    List<LotTrace.OrderLeg> orders(UUID lotId, Set<String> warehouses);

    long countMovements(UUID lotId, Set<String> warehouses, Instant from, Instant to);

    List<LotTrace.Movement> movements(UUID lotId, Set<String> warehouses, Instant from, Instant to, long offset,
                                      int limit);
}
