package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.DamagedWriteOff;
import com.stockflow.inventory.api.DamagedWriteOffs;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link DamagedWriteOffs}: one statement over {@code inventory.stock_adjustment}. A production order's lot is found
 * by its production order ({@code stock_lot.production_order_id}), not by its number alone, and an adjustment belongs
 * to it by the lot's SKU and number - the pair {@code ck_stock_lot_production_order} holds to the production order's
 * code. Only a posted write-off of a whole carton counts: an adjustment of before the whole-carton rule names no
 * carton, and would be no carton to make up.
 */
@Component
class DamagedWriteOffsAdapter implements DamagedWriteOffs {

    private final NamedParameterJdbcTemplate jdbc;

    DamagedWriteOffsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DamagedWriteOff> ofProductionOrder(UUID productionOrderId) {
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        return jdbc.query("""
                        SELECT a.id, a.adjustment_number, a.sku, a.stock_package_id, p.lpn, a.posted_at
                          FROM inventory.stock_adjustment a
                          JOIN inventory.stock_lot l ON l.sku = a.sku AND l.lot_number = a.lot_number
                          JOIN inventory.stock_package p ON p.id = a.stock_package_id
                         WHERE l.production_order_id = :productionOrder
                           AND a.reason_code = 'DAMAGED' AND a.status = 'POSTED' AND a.quantity_delta < 0
                         ORDER BY a.posted_at, a.id""",
                Map.of("productionOrder", productionOrderId),
                (rs, row) -> {
                    Timestamp posted = rs.getTimestamp("posted_at");
                    return new DamagedWriteOff(rs.getObject("id", UUID.class), rs.getString("adjustment_number"),
                            rs.getString("sku"), rs.getObject("stock_package_id", UUID.class), rs.getString("lpn"),
                            posted == null ? null : posted.toInstant());
                });
    }
}
