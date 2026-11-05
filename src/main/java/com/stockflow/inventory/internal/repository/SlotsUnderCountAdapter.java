package com.stockflow.inventory.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** {@link SlotsUnderCount} in SQL, over the one busy predicate ({@link CountBusy}). */
@Repository
class SlotsUnderCountAdapter implements SlotsUnderCount {

    private final NamedParameterJdbcTemplate jdbc;

    SlotsUnderCountAdapter(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public Set<UUID> stockItemsUnderCount(Collection<UUID> stockItemIds) {
        if (stockItemIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT s.id FROM inventory.stock_item s
                 WHERE s.id IN (:ids) AND %s""".formatted(CountBusy.stockRowInBusySlot("s")),
                new MapSqlParameterSource("ids", Set.copyOf(stockItemIds)), UUID.class));
    }

    /** {@code stock_adjustment} names its slot with the same columns as a stock row. */
    @Override
    public boolean adjustmentUnderCount(UUID adjustmentId) {
        Boolean held = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM inventory.stock_adjustment a WHERE a.id = :id AND %s)""".formatted(
                        CountBusy.stockRowInBusySlot("a")), new MapSqlParameterSource("id", adjustmentId),
                Boolean.class);
        return Boolean.TRUE.equals(held);
    }

    @Override
    public Set<String> locationsUnderCount(UUID lotId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT s.location_code FROM inventory.stock_item s
                 WHERE s.lot_id = :lotId AND %s""".formatted(CountBusy.stockRowInBusySlot("s")),
                new MapSqlParameterSource("lotId", lotId), String.class));
    }
}
