package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** The purchasing columns of {@code inventory.inventory_items} (V20261016001100). */
@Repository
public class ItemPurchasingRepository {

    private final JdbcTemplate jdbc;

    public ItemPurchasingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventoryItemPurchasing> find(String sku) {
        return jdbc.query("""
                select sku, version, default_supplier_id, reference_purchase_price, reference_purchase_currency,
                       lead_time_days, moq
                from inventory.inventory_items where sku = ?""", (rs, n) ->
                new InventoryItemPurchasing(rs.getString("sku"), rs.getLong("version"), new ItemPurchasing(
                        rs.getObject("default_supplier_id", UUID.class), rs.getBigDecimal("reference_purchase_price"),
                        rs.getString("reference_purchase_currency"), rs.getObject("lead_time_days", Integer.class),
                        rs.getObject("moq", Integer.class))), sku).stream().findFirst();
    }

    /** False when the version moved on: someone else changed the item since it was read. */
    public boolean save(String sku, long version, ItemPurchasing p, String actor) {
        return jdbc.update("""
                update inventory.inventory_items set default_supplier_id=?, reference_purchase_price=?,
                    reference_purchase_currency=?, lead_time_days=?, moq=?, version=version+1,
                    last_modified_at=now(), last_modified_by=?
                where sku=? and version=?
                """, p.defaultSupplierId(), p.referencePurchasePrice(), p.referencePurchaseCurrency(),
                p.leadTimeDays(), p.moq(), actor, sku, version) == 1;
    }
}
