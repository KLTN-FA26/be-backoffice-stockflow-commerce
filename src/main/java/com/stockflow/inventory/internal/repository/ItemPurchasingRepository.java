package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryItemPurchasing;
import com.stockflow.inventory.api.ItemPurchasing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The purchasing columns of {@code inventory.inventory_items} (V20261019000200). */
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

    /** The item's SKU, to take its per-SKU lock. */
    public Optional<String> skuOf(UUID inventoryItemId) {
        return jdbc.queryForList("select sku from inventory.inventory_items where id = ?", String.class,
                inventoryItemId).stream().findFirst();
    }

    public Map<UUID, UUID> defaultSuppliers(Collection<UUID> inventoryItemIds) {
        Map<UUID, UUID> found = new HashMap<>();
        if (inventoryItemIds.isEmpty()) return found;
        UUID[] ids = inventoryItemIds.stream().distinct().toArray(UUID[]::new);
        jdbc.query(con -> {
            var statement = con.prepareStatement("""
                    select id, default_supplier_id from inventory.inventory_items
                    where id = any (?) and default_supplier_id is not null""");
            Array array = con.createArrayOf("uuid", ids);
            statement.setArray(1, array);
            return statement;
        }, rs -> {
            found.put(rs.getObject("id", UUID.class), rs.getObject("default_supplier_id", UUID.class));
        });
        return found;
    }

    /** Sets the default supplier; the version moves only when the value changes. */
    public void assignDefaultSupplier(UUID inventoryItemId, UUID supplierId, String actor) {
        jdbc.update("""
                update inventory.inventory_items set default_supplier_id=?, version=version+1,
                    last_modified_at=now(), last_modified_by=?
                where id=? and default_supplier_id is distinct from ?
                """, supplierId, actor, inventoryItemId, supplierId);
    }

    /** Clears the default supplier only while it is {@code supplierId}. */
    public void clearDefaultSupplier(UUID inventoryItemId, UUID supplierId, String actor) {
        jdbc.update("""
                update inventory.inventory_items set default_supplier_id=null, version=version+1,
                    last_modified_at=now(), last_modified_by=?
                where id=? and default_supplier_id=?
                """, actor, inventoryItemId, supplierId);
    }
}
