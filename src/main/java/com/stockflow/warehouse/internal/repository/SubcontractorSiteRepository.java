package com.stockflow.warehouse.internal.repository;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The storage location of a print subcontractor for one sending warehouse (V20261014000470, decision Q7). Plain
 * SQL: a site is a pair of rows written once and never changed, no aggregate behaviour to map.
 */
@Repository
public class SubcontractorSiteRepository {

    private final JdbcTemplate jdbc;

    SubcontractorSiteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The site's location code, created when absent. A transaction-scoped advisory lock on the pair serialises two
     * first sends, so the second waits and finds the first one's site instead of failing on the unique key.
     */
    public String locationOf(UUID warehouseId, UUID supplierId, String supplierCode) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { },
                "subcontractor-site:" + warehouseId + ":" + supplierId);
        Optional<String> existing = jdbc.query("""
                        SELECT l.location_code FROM warehouse.subcontractor_site s
                          JOIN warehouse.storage_location l ON l.id = s.location_id
                         WHERE s.warehouse_id = ? AND s.supplier_id = ?""",
                (rs, n) -> rs.getString(1), warehouseId, supplierId).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        String prefix = jdbc.queryForObject("SELECT prefix FROM warehouse.warehouse WHERE id = ?", String.class,
                warehouseId);
        String code = prefix + "-SUB-" + codePart(supplierCode);
        UUID location = Identifiers.newId();
        jdbc.update("""
                INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class,
                    capacity_units, max_weight, is_pickable, is_putaway_target, status, created_by)
                VALUES (?, ?, 'SUBCONTRACTOR', ?, 'NORMAL', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'system')""",
                location, warehouseId, code);
        jdbc.update("""
                INSERT INTO warehouse.subcontractor_site (id, warehouse_id, supplier_id, location_id, created_by)
                VALUES (?, ?, ?, ?, 'system')""", Identifiers.newId(), warehouseId, supplierId, location);
        return code;
    }

    /** The supplier's code as a location code part: [A-Z0-9], at most 20 characters. */
    private static String codePart(String supplierCode) {
        String part = supplierCode == null ? "" : supplierCode.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (part.isEmpty()) {
            throw new IllegalArgumentException("A subcontractor's location needs its supplier code");
        }
        return part.length() > 20 ? part.substring(0, 20) : part;
    }
}
