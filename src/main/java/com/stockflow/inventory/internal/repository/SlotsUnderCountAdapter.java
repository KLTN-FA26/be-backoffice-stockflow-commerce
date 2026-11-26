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
    public Set<UUID> cartonsUnderCount(Collection<UUID> stockPackageIds) {
        if (stockPackageIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT p.id FROM inventory.stock_package p
                 WHERE p.id IN (:ids) AND %s""".formatted(CountBusy.cartonInBusySlot("p.id")),
                new MapSqlParameterSource("ids", Set.copyOf(stockPackageIds)), UUID.class));
    }

    /** An adjustment is in the slot of the carton it names. */
    @Override
    public boolean adjustmentUnderCount(UUID adjustmentId) {
        Boolean held = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM inventory.stock_adjustment a WHERE a.id = :id AND %s)""".formatted(
                        CountBusy.cartonInBusySlot("a.stock_package_id")), new MapSqlParameterSource("id", adjustmentId),
                Boolean.class);
        return Boolean.TRUE.equals(held);
    }

    @Override
    public Set<String> locationsUnderCount(UUID lotId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.location_code FROM inventory.stock_package p
                 WHERE p.lot_id = :lotId AND %s""".formatted(CountBusy.cartonInBusySlot("p.id")),
                new MapSqlParameterSource("lotId", lotId), String.class));
    }
}
