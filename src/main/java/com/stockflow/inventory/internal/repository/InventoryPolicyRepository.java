package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL over {@code inventory.inventory_items}: the SKU advisory lock, whether items are in use, and the logistics.
 * Writes only the inventory schema. The same advisory lock is taken by the stock-row trigger
 * ({@code inventory.enforce_sku_policy}).
 *
 * <p>The name is older than its job: the per-SKU stock policy it once read and wrote (removal strategy, lot and serial
 * tracking) went in team plan 5-5 - every SKU is lot-tracked, without serial numbers, first in first out (kltn-docs 01
 * BR-17) - and the trigger states that fixed policy for every SKU.</p>
 */
@Repository
public class InventoryPolicyRepository {
    private final JdbcTemplate jdbc;

    public InventoryPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String sku) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 701))", rs -> {}, sku);
    }

    /** Deletes the items of these SKUs; a foreign key that still points at one refuses the whole statement. */
    public void deleteItems(java.util.Collection<String> skus) {
        if (skus.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(skus.size(), "?"));
        jdbc.update("delete from inventory.inventory_items where sku in (" + placeholders + ")", skus.toArray());
    }

    /**
     * The SKUs among these whose item some document uses ({@code inventory.item_in_use}: stock, the ledger,
     * adjustments, counts, warehouse tasks, pick lines, a price, procurement), in SKU order. The same
     * function guards a SKU rename, so a delete and a rename refuse the same items.
     */
    public java.util.List<String> skusInUse(java.util.Collection<String> skus) {
        if (skus.isEmpty()) return java.util.List.of();
        String placeholders = String.join(",", Collections.nCopies(skus.size(), "?"));
        return jdbc.queryForList("select sku from inventory.inventory_items where sku in (" + placeholders
                + ") and inventory.item_in_use(id, sku) order by sku", String.class, skus.toArray());
    }

    public Optional<InventoryControlItem> findItem(String sku) {
        return jdbc.query("select * from inventory.inventory_items where sku = ?", (rs, n) ->
                new InventoryControlItem(rs.getString("sku"), rs.getString("unit_of_measure"), rs.getLong("version")),
                sku).stream().findFirst();
    }

    /** One inventory item, its logistics and the version, as they stood together. */
    public record ItemRow(java.util.UUID id, String sku, long version, ItemLogistics logistics) {
    }

    /**
     * The whole item in one SELECT. Reading the logistics and the version separately lets a concurrent save land
     * between the reads, and the result would label one save's values with another's version.
     */
    public Optional<ItemRow> findById(java.util.UUID id) {
        return jdbc.query("select * from inventory.inventory_items where id = ?", (rs, n) ->
                new ItemRow(rs.getObject("id", java.util.UUID.class), rs.getString("sku"), rs.getLong("version"),
                        logistics(rs)), id).stream().findFirst();
    }

    /** The items with these SKUs, in one SELECT; a SKU without an item is simply absent. */
    public List<ItemRow> findBySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from inventory.inventory_items where sku = any (?)", ps ->
                ps.setArray(1, ps.getConnection().createArrayOf("text", skus.toArray())), (rs, n) ->
                new ItemRow(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getLong("version"),
                        logistics(rs)));
    }

    private static ItemLogistics logistics(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemLogistics(
                rs.getString("unit_of_measure"), rs.getString("barcode"),
                rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"),
                rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                rs.getBigDecimal("package_weight_kg"), rs.getBigDecimal("package_length_cm"),
                rs.getBigDecimal("package_width_cm"), rs.getBigDecimal("package_height_cm"),
                rs.getInt("package_count"), rs.getInt("pack_size"), rs.getBoolean("rotatable"));
    }

    /** The SKU of the item with this id; empty when there is none. */
    public Optional<String> findSku(java.util.UUID id) {
        return jdbc.queryForList("select sku from inventory.inventory_items where id = ?", String.class, id)
                .stream()
                .findFirst();
    }

    public Optional<InventoryItemLogistics> findLogistics(String sku) {
        return jdbc.query("select * from inventory.inventory_items where sku = ?", (rs, n) ->
                new InventoryItemLogistics(rs.getString("sku"), rs.getLong("version"), logistics(rs)), sku)
                .stream().findFirst();
    }

    /**
     * The SKU's carton spec: its one active packing unit, or the default shipping one among several - the rule
     * {@code StockPackageRepository.cartonFactorOf} and V20261111000110 use - with the item's rotatable flag. Empty for
     * an unknown SKU, or one with no such unit.
     */
    public Optional<com.stockflow.inventory.api.CartonSpec> findCartonSpec(String sku) {
        record Unit(com.stockflow.inventory.api.CartonSpec spec, boolean defaultShipping) { }
        List<Unit> units = jdbc.query("""
                select u.unit_of_measure, u.factor, u.length_cm, u.width_cm, u.height_cm, u.weight_kg,
                       u.default_shipping, i.rotatable
                  from inventory.uom_conversions u
                  join inventory.inventory_items i on i.id = u.inventory_item_id
                 where i.sku = ? and u.active and not u.is_base
                """, (rs, n) -> new Unit(new com.stockflow.inventory.api.CartonSpec(sku,
                        rs.getString("unit_of_measure"), rs.getInt("factor"), rs.getBigDecimal("length_cm"),
                        rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"), rs.getBigDecimal("weight_kg"),
                        rs.getBoolean("rotatable")),
                        rs.getBoolean("default_shipping")), sku);
        List<Unit> chosen = units.size() == 1 ? units : units.stream().filter(Unit::defaultShipping).toList();
        return chosen.size() == 1 ? Optional.of(chosen.getFirst().spec()) : Optional.empty();
    }

    /** Whether any stock of the SKU is held anywhere, in any status. */
    public boolean holdsStock(String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from inventory.stock_item where sku = ? and (on_hand > 0 or reserved > 0))",
                Boolean.class, sku));
    }

    /** False when the version moved on: someone else changed the item since it was read. */
    public boolean saveLogistics(String sku, long version, ItemLogistics l, String actor) {
        return jdbc.update("""
                update inventory.inventory_items set unit_of_measure=?, barcode=?, weight_kg=?, length_cm=?,
                    width_cm=?, height_cm=?, package_weight_kg=?, package_length_cm=?, package_width_cm=?,
                    package_height_cm=?, package_count=?, pack_size=?, rotatable=?, version=version+1,
                    last_modified_at=now(),
                    last_modified_by=?
                where sku=? and version=?
                """, l.unitOfMeasure(), l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.rotatable(), actor, sku, version) == 1;
    }

    /** Whether a packing unit of the SKU (not its base unit) already uses this unit code. */
    public boolean packingUnitUses(String sku, String unitOfMeasure) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from inventory.uom_conversions c
                    join inventory.inventory_items i on i.id = c.inventory_item_id
                    where i.sku = ? and not c.is_base and c.unit_of_measure = ?)
                """, Boolean.class, sku, unitOfMeasure));
    }
}
