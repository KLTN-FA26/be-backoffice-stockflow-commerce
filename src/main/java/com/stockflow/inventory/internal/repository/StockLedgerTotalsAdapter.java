package com.stockflow.inventory.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.StockLedgerTotals;
import com.stockflow.inventory.api.StockMovementTotals;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link StockLedgerTotals}: one statement over {@code inventory.stock_movement}, the ledger every change of stock
 * writes a line to (kltn-docs 20 BR-16) and nothing ever updates ({@code tg_stock_movement_append_only}).
 *
 * <p>Each line is read as two signed sides: {@code +quantity} for the warehouse of {@code to_location_code},
 * {@code -quantity} for the warehouse of {@code from_location_code}, a side dropped when the other end is in the same
 * warehouse (or there is none). That is {@code delta_W = quantity × ([to in W] − [from in W])} without a row per
 * warehouse, and it is why moves inside a warehouse - putaway, carton moves, status changes, picks to the staging
 * area, a put-back from staging - vanish without being named: they are the same warehouse at both ends. The kinds
 * are then told apart by movement type and reference, on the signed side:</p>
 * <ul>
 *   <li>received: {@code RECEIPT} of a {@code GOODS_RECEIPT_LINE} (a production order's goods, 03);</li>
 *   <li>returned: {@code RECEIPT} of a {@code GOODS_RECEIPT} (a return receipt's cartons, 4D-2,
 *       {@code ck_stock_movement_return_receipt});</li>
 *   <li>put back: {@code PUT_BACK} from no location (a picked carton booked back, 07 §4.5);</li>
 *   <li>picked: {@code PICK};</li>
 *   <li>adjusted up / down: {@code ADJUSTMENT} and {@code COUNT_CORRECTION} by sign (20 §4.3);</li>
 *   <li>other in / out: every remaining in or out - the historical types of removed flows (transfers, scrap, supplier
 *       returns, consumption, returns QC'd loose) - so that opening plus the columns is always the closing.</li>
 * </ul>
 *
 * <p>The period's bounds are business days in Asia/Ho_Chi_Minh, as instants: {@code [from 00:00, to + 1 00:00)}
 * Vietnam time. {@code ix_stock_movement_sku_time}, {@code ix_stock_movement_from} and {@code ix_stock_movement_to}
 * serve the SKU, warehouse and time bounds.</p>
 */
@Component
class StockLedgerTotalsAdapter implements StockLedgerTotals {

    private final NamedParameterJdbcTemplate jdbc;

    StockLedgerTotalsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockMovementTotals> movementTotals(LocalDate from, LocalDate to,
                                                    Collection<String> warehousePrefixesOrNull,
                                                    Collection<String> skusOrNull) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The period starts after it ends: " + from + " > " + to);
        }
        Optional<Set<String>> warehouses = warehouses(warehousePrefixesOrNull);
        if (warehouses.map(Set::isEmpty).orElse(false) || (skusOrNull != null && skusOrNull.isEmpty())) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("since", OffsetDateTime.ofInstant(from.atStartOfDay(BusinessCalendar.ZONE).toInstant(),
                        ZoneOffset.UTC))
                .addValue("until", OffsetDateTime.ofInstant(to.plusDays(1).atStartOfDay(BusinessCalendar.ZONE)
                        .toInstant(), ZoneOffset.UTC));
        String skuClause = "";
        if (skusOrNull != null) {
            skuClause = "AND m.sku IN (:skus)";
            params.addValue("skus", List.copyOf(new LinkedHashSet<>(skusOrNull)));
        }
        String locationClause = "";
        String warehouseClause = "";
        if (warehouses.isPresent()) {
            locationClause = "AND (" + WarehouseScope.locationsInSql("m.from_location_code", warehouses.get(),
                    "fromPrefix", params) + " OR " + WarehouseScope.locationsInSql("m.to_location_code",
                    warehouses.get(), "toPrefix", params) + ")";
            warehouseClause = "WHERE warehouse IN (:warehouses)";
            params.addValue("warehouses", List.copyOf(warehouses.get()));
        }
        return jdbc.query("""
                WITH moved AS (
                    SELECT m.sku, m.movement_type, m.reference_type, m.quantity, m.occurred_at,
                           m.from_location_code IS NULL AS from_nowhere,
                           upper(split_part(m.from_location_code, '-', 1)) AS from_warehouse,
                           upper(split_part(m.to_location_code, '-', 1)) AS to_warehouse
                      FROM inventory.stock_movement m
                     WHERE m.occurred_at < :until
                       %s
                       %s
                ), sides AS (
                    SELECT to_warehouse AS warehouse, sku, movement_type, reference_type, from_nowhere, occurred_at,
                           quantity AS delta
                      FROM moved
                     WHERE to_warehouse IS NOT NULL AND to_warehouse IS DISTINCT FROM from_warehouse
                    UNION ALL
                    SELECT from_warehouse, sku, movement_type, reference_type, from_nowhere, occurred_at,
                           -quantity
                      FROM moved
                     WHERE from_warehouse IS NOT NULL AND from_warehouse IS DISTINCT FROM to_warehouse
                ), kinds AS (
                    SELECT warehouse, sku, delta, occurred_at >= :since AS in_period,
                           movement_type = 'RECEIPT' AND reference_type = 'GOODS_RECEIPT_LINE' AS received,
                           movement_type = 'RECEIPT' AND reference_type = 'GOODS_RECEIPT' AS returned,
                           movement_type = 'PUT_BACK' AND from_nowhere AS put_back,
                           movement_type = 'PICK' AS picked,
                           movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION') AS adjusted
                      FROM sides
                     %s
                )
                SELECT warehouse, sku,
                       COALESCE(SUM(delta) FILTER (WHERE NOT in_period), 0) AS opening,
                       COALESCE(SUM(delta) FILTER (WHERE in_period AND delta > 0 AND received), 0) AS received,
                       COALESCE(SUM(delta) FILTER (WHERE in_period AND delta > 0 AND returned), 0) AS returned,
                       COALESCE(SUM(delta) FILTER (WHERE in_period AND delta > 0 AND put_back), 0) AS put_back,
                       COALESCE(-SUM(delta) FILTER (WHERE in_period AND delta < 0 AND picked), 0) AS picked,
                       COALESCE(SUM(delta) FILTER (WHERE in_period AND delta > 0 AND adjusted), 0) AS adjusted_in,
                       COALESCE(-SUM(delta) FILTER (WHERE in_period AND delta < 0 AND adjusted), 0) AS adjusted_out,
                       COALESCE(SUM(delta) FILTER (WHERE in_period AND delta > 0
                                AND NOT (received OR returned OR put_back OR adjusted)), 0) AS other_in,
                       COALESCE(-SUM(delta) FILTER (WHERE in_period AND delta < 0
                                AND NOT (picked OR adjusted)), 0) AS other_out,
                       COALESCE(SUM(delta), 0) AS closing
                  FROM kinds
                 GROUP BY warehouse, sku
                 ORDER BY sku, warehouse""".formatted(skuClause, locationClause, warehouseClause), params,
                (rs, n) -> new StockMovementTotals(rs.getString("warehouse"), rs.getString("sku"),
                        rs.getLong("opening"), rs.getLong("received"), rs.getLong("returned"),
                        rs.getLong("put_back"), rs.getLong("picked"), rs.getLong("adjusted_in"),
                        rs.getLong("adjusted_out"), rs.getLong("other_in"), rs.getLong("other_out"),
                        rs.getLong("closing")));
    }

    /**
     * The warehouses to read: those asked, cut down to the caller's own when the caller is limited to warehouses
     * (BR-SEC-002); empty when none is left. Empty optional: every warehouse.
     */
    private static Optional<Set<String>> warehouses(Collection<String> asked) {
        Optional<Set<String>> wanted = asked == null ? Optional.empty() : Optional.of(upper(asked));
        Optional<AssignedWarehouses> limited = WarehouseScope.restriction();
        if (limited.isEmpty()) {
            return wanted;
        }
        Set<String> own = upper(limited.get().prefixes());
        return Optional.of(wanted.map(set -> {
            Set<String> both = new LinkedHashSet<>(set);
            both.retainAll(own);
            return both;
        }).orElse(own));
    }

    private static Set<String> upper(Collection<String> prefixes) {
        Set<String> set = new LinkedHashSet<>();
        prefixes.stream().filter(Objects::nonNull).map(p -> p.trim().toUpperCase(Locale.ROOT))
                .filter(p -> !p.isEmpty()).forEach(set::add);
        return set;
    }
}
