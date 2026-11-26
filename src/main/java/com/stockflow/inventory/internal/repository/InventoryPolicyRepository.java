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
    private final VariantRotations rotations;
    private final SkuVariantLookup variants;

    public InventoryPolicyRepository(JdbcTemplate jdbc, VariantRotations rotations, SkuVariantLookup variants) {
        this.jdbc = jdbc;
        this.rotations = rotations;
        this.variants = variants;
    }

    public void lock(String sku) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 701))", rs -> {}, sku);
    }

    /**
     * Makes the item of a customer's SKU, packed as {@code variantId}, unless the SKU has one (owner decision Q5): an
     * item another order made meanwhile is kept, and the caller reads back which variant it records.
     */
    public void openItem(UUID id, String sku, UUID variantId, String actor) {
        jdbc.update("""
                insert into inventory.inventory_items (id, sku, variant_id, created_by) values (?, ?, ?, ?)
                on conflict (sku) do nothing""", id, sku, variantId, actor);
    }

    /** Deletes the items of these SKUs; a foreign key that still points at one refuses the whole statement. */
    public void deleteItems(java.util.Collection<String> skus) {
        if (skus.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(skus.size(), "?"));
        jdbc.update("delete from inventory.inventory_items where sku in (" + placeholders + ")", skus.toArray());
    }

    /**
     * The SKUs among these whose item some document uses ({@code inventory.item_in_use}: stock, the ledger,
     * adjustments, counts, warehouse tasks, pick lines, a price, procurement), in SKU order. A SKU without an item
     * (a catalogue variant, a customer's SKU no order named) is absent.
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

    /**
     * One inventory item, its logistics and the version, as they stood together, and the catalogue variant whose
     * carton it is packed in ({@code variant_id}, V20270104000100).
     */
    public record ItemRow(java.util.UUID id, String sku, long version, ItemLogistics logistics, UUID variantId) {
    }

    /**
     * The whole item in one SELECT. Reading the logistics and the version separately lets a concurrent save land
     * between the reads, and the result would label one save's values with another's version.
     */
    public Optional<ItemRow> findById(java.util.UUID id) {
        return jdbc.query("select * from inventory.inventory_items where id = ?", (rs, n) -> itemRow(rs), id)
                .stream().findFirst();
    }

    /** The items with these SKUs, in one SELECT; a SKU without an item is simply absent. */
    public List<ItemRow> findBySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from inventory.inventory_items where sku = any (?)", ps ->
                ps.setArray(1, ps.getConnection().createArrayOf("text", skus.toArray())), (rs, n) -> itemRow(rs));
    }

    private static ItemRow itemRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getLong("version"), logistics(rs),
                rs.getObject("variant_id", UUID.class));
    }

    private static ItemLogistics logistics(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemLogistics(rs.getString("unit_of_measure"), rs.getString("barcode"));
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
     * The SKU's carton spec: its catalogue variant's one active carton ({@code uk_uom_conversions_one_carton}; owner
     * decision Q1) with the variant's rotatable flag (Q9), whether or not the SKU has an item yet
     * ({@link SkuVariantLookup}). Empty for an unknown SKU, or one whose variant has no active carton.
     */
    public Optional<com.stockflow.inventory.api.CartonSpec> findCartonSpec(String sku) {
        return Optional.ofNullable(findCartonSpecs(List.of(sku)).get(sku));
    }

    /**
     * {@link #findCartonSpec} for many SKUs in one read (BE1: the capacity board sizes every SKU it counts by the same
     * rule). A SKU with no carton spec is absent, and so is one with several active units - which
     * {@code uk_uom_conversions_one_carton} refuses - as
     * {@code UomConversion.cartonOf} answers: nothing is guessed.
     */
    public java.util.Map<String, com.stockflow.inventory.api.CartonSpec> findCartonSpecs(Collection<String> skus) {
        if (skus.isEmpty()) {
            return java.util.Map.of();
        }
        record Row(String sku, UUID variantId, String unit, int factor, java.math.BigDecimal length,
                   java.math.BigDecimal width, java.math.BigDecimal height, java.math.BigDecimal weight) { }
        java.util.Map<String, UUID> variantOf = variants.of(skus);
        if (variantOf.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, List<Row>> byVariant = new java.util.HashMap<>();
        jdbc.query("""
                select u.variant_id, u.unit_of_measure, u.factor, u.length_cm, u.width_cm, u.height_cm, u.weight_kg
                  from inventory.uom_conversions u
                 where u.variant_id = any (?) and u.active
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid",
                        variantOf.values().stream().distinct().toArray())),
                rs -> {
                    UUID variant = rs.getObject("variant_id", UUID.class);
                    byVariant.computeIfAbsent(variant, k -> new java.util.ArrayList<>()).add(new Row(null, variant,
                            rs.getString("unit_of_measure"), rs.getInt("factor"), rs.getBigDecimal("length_cm"),
                            rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                            rs.getBigDecimal("weight_kg")));
                });
        List<Row> units = new java.util.ArrayList<>();
        variantOf.forEach((sku, variant) -> byVariant.getOrDefault(variant, List.of()).forEach(r -> units.add(
                new Row(sku, variant, r.unit(), r.factor(), r.length(), r.width(), r.height(), r.weight()))));
        java.util.Map<UUID, Boolean> rotatable = rotations.of(units.stream().map(Row::variantId).toList());
        java.util.Map<String, com.stockflow.inventory.api.CartonSpec> specs = new java.util.HashMap<>();
        java.util.Set<String> several = new java.util.HashSet<>();
        for (Row row : units) {
            var spec = new com.stockflow.inventory.api.CartonSpec(row.sku(), row.unit(), row.factor(), row.length(),
                    row.width(), row.height(), row.weight(), VariantRotations.rotatable(rotatable, row.variantId()));
            if (specs.putIfAbsent(spec.sku(), spec) != null) {
                several.add(spec.sku());
            }
        }
        specs.keySet().removeAll(several);
        return specs;
    }

    /**
     * The carton each of these LPNs was received in ({@code stock_package.uom_conversion_id}, fixed at receipt), as it
     * is measured now, keyed by LPN, with the carton's own SKU and its variant's rotatable flag (Q9). A carton received
     * in a carton row since retired keeps that row: it is measured as what it is, not as the variant's new carton.
     */
    public java.util.Map<String, com.stockflow.inventory.api.CartonSpec> findCartonSpecsByLpn(Collection<String> lpns) {
        if (lpns.isEmpty()) {
            return java.util.Map.of();
        }
        record Row(String lpn, String sku, UUID variantId, String unit, int factor, java.math.BigDecimal length,
                   java.math.BigDecimal width, java.math.BigDecimal height, java.math.BigDecimal weight) { }
        List<Row> rows = jdbc.query("""
                select p.lpn, i.sku, u.variant_id, u.unit_of_measure, u.factor, u.length_cm, u.width_cm, u.height_cm,
                       u.weight_kg
                  from inventory.stock_package p
                  join inventory.inventory_items i on i.id = p.inventory_item_id
                  join inventory.uom_conversions u on u.id = p.uom_conversion_id
                 where p.lpn = any (?)
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", lpns.stream().distinct().toArray())),
                (rs, n) -> new Row(rs.getString("lpn"), rs.getString("sku"), rs.getObject("variant_id", UUID.class),
                        rs.getString("unit_of_measure"), rs.getInt("factor"), rs.getBigDecimal("length_cm"),
                        rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"), rs.getBigDecimal("weight_kg")));
        java.util.Map<UUID, Boolean> rotatable = rotations.of(rows.stream().map(Row::variantId).toList());
        java.util.Map<String, com.stockflow.inventory.api.CartonSpec> specs = new java.util.HashMap<>();
        for (Row row : rows) {
            specs.put(row.lpn(), new com.stockflow.inventory.api.CartonSpec(row.sku(), row.unit(), row.factor(),
                    row.length(), row.width(), row.height(), row.weight(),
                    VariantRotations.rotatable(rotatable, row.variantId())));
        }
        return specs;
    }

    /** Whether any carton of the SKU is in stock anywhere, in any stock status. */
    public boolean holdsStock(String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists(select 1 from inventory.stock_package p
                                join inventory.inventory_items i on i.id = p.inventory_item_id
                               where i.sku = ? and p.status = 'IN_STOCK')""",
                Boolean.class, sku));
    }

    /** False when the version moved on: someone else changed the item since it was read. */
    public boolean saveLogistics(String sku, long version, ItemLogistics l, String actor) {
        return jdbc.update("""
                update inventory.inventory_items set unit_of_measure=?, barcode=?, version=version+1,
                    last_modified_at=now(),
                    last_modified_by=?
                where sku=? and version=?
                """, l.unitOfMeasure(), l.barcode(), actor, sku, version) == 1;
    }

    /** Whether a carton of the SKU's variant already uses this unit code. */
    public boolean packingUnitUses(String sku, String unitOfMeasure) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from inventory.inventory_items i
                    join inventory.uom_conversions c on c.variant_id = i.variant_id
                    where i.sku = ? and c.unit_of_measure = ?)
                """, Boolean.class, sku, unitOfMeasure));
    }
}
