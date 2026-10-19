package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.StorageClass;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plain SQL over {@code inventory.inventory_items}: a list needs eight columns and one ordering, and the optional
 * filters are appended only when asked for, so no condition is bound that was not requested.
 */
@Repository
class InventoryItemSearchAdapter implements InventoryItemSearch {

    private final JdbcTemplate jdbc;

    InventoryItemSearchAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<Row> items(Criteria criteria, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (criteria.sku() != null) {
            where.append(" AND sku = ?");
            args.add(criteria.sku());
        }
        if (criteria.q() != null) {
            String pattern = "%" + escapeLike(criteria.q()) + "%";
            where.append(" AND (sku ILIKE ? ESCAPE '\\' OR barcode ILIKE ? ESCAPE '\\')");
            args.add(pattern);
            args.add(pattern);
        }
        if (criteria.storageClass() != null) {
            where.append(" AND storage_class = ?");
            args.add(criteria.storageClass().name());
        }
        if (criteria.policyConfigured() != null) {
            where.append(" AND policy_configured = ?");
            args.add(criteria.policyConfigured());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<Row> content = jdbc.query(
                "SELECT id, sku, unit_of_measure, barcode, storage_class, lot_tracked, serial_tracked,"
                        + " policy_configured FROM inventory.inventory_items" + where
                        + " ORDER BY sku LIMIT ? OFFSET ?",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("unit_of_measure"), rs.getString("barcode"),
                        StorageClass.valueOf(rs.getString("storage_class")), rs.getBoolean("lot_tracked"),
                        rs.getBoolean("serial_tracked"), rs.getBoolean("policy_configured")),
                pageArgs.toArray());
        // A partial page already gives the total (its offset plus its length), so the count, a second scan with the
        // same ILIKE filters, runs only for a full page or an empty page past the first.
        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Long total = jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items" + where, Long.class,
                    args.toArray());
            return total == null ? 0 : total;
        });
    }

    /** Backslash first, or the escapes added for the other two would themselves be escaped. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
