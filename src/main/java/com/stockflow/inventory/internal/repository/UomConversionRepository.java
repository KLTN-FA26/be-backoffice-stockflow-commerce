package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code inventory.uom_conversions} (V20261019000300), always read with its item: the SKU, and for the
 * base unit the size and weight, are the item's.
 */
@Repository
public class UomConversionRepository {

    private static final String SELECT = """
            select c.id, i.sku, c.unit_of_measure, c.factor, c.is_base, c.default_purchase, c.active, c.version,
                   c.default_shipping,
                   case when c.is_base then i.weight_kg else c.weight_kg end as weight_kg,
                   case when c.is_base then i.length_cm else c.length_cm end as length_cm,
                   case when c.is_base then i.width_cm else c.width_cm end as width_cm,
                   case when c.is_base then i.height_cm else c.height_cm end as height_cm
            from inventory.uom_conversions c join inventory.inventory_items i on i.id = c.inventory_item_id
            """;

    private static final RowMapper<UomConversion> ROW = (rs, n) -> new UomConversion(
            rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("unit_of_measure"), rs.getInt("factor"),
            rs.getBoolean("is_base"), rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"),
            rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"), rs.getBoolean("default_purchase"),
            rs.getBoolean("active"), rs.getLong("version"), rs.getBoolean("default_shipping"));

    private final JdbcTemplate jdbc;

    public UomConversionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> itemId(String sku) {
        return jdbc.query("select id from inventory.inventory_items where sku = ?",
                (rs, n) -> rs.getObject("id", UUID.class), sku).stream().findFirst();
    }

    public long count(String sku) {
        Long count = jdbc.queryForObject("""
                select count(*) from inventory.uom_conversions c
                join inventory.inventory_items i on i.id = c.inventory_item_id where i.sku = ?""", Long.class, sku);
        return count == null ? 0 : count;
    }

    /** The base unit first, then by code. */
    public List<UomConversion> page(String sku, int limit, long offset) {
        return jdbc.query(SELECT + " where i.sku = ? order by c.is_base desc, c.unit_of_measure limit ? offset ?",
                ROW, sku, limit, offset);
    }

    public Optional<UomConversion> find(String sku, UUID id) {
        return jdbc.query(SELECT + " where i.sku = ? and c.id = ?", ROW, sku, id).stream().findFirst();
    }

    public List<UomConversion> find(String sku, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT + " where i.sku = ? and c.id = any (?)", ps -> {
            ps.setString(1, sku);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        }, ROW);
    }

    /** The units of each of these SKUs, the base unit first, then by code; a SKU without units is absent. */
    public java.util.Map<String, List<UomConversion>> bySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return java.util.Map.of();
        }
        List<UomConversion> rows = jdbc.query(
                SELECT + " where i.sku = any (?) order by i.sku, c.is_base desc, c.unit_of_measure", ps ->
                        ps.setArray(1, ps.getConnection().createArrayOf("text", skus.toArray())), ROW);
        java.util.Map<String, List<UomConversion>> out = new java.util.HashMap<>();
        rows.forEach(u -> out.computeIfAbsent(u.sku(), k -> new java.util.ArrayList<>()).add(u));
        return out;
    }

    public Optional<UomConversion> base(String sku) {
        return jdbc.query(SELECT + " where i.sku = ? and c.is_base", ROW, sku).stream().findFirst();
    }

    public boolean unitTaken(UUID itemId, String unitOfMeasure, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from inventory.uom_conversions
                    where inventory_item_id = ? and unit_of_measure = ? and id is distinct from ?)""",
                Boolean.class, itemId, unitOfMeasure, except));
    }

    /** Unmark the default purchase unit of the item, except {@code keep}. */
    public void clearDefaultPurchase(UUID itemId, UUID keep, String actor) {
        jdbc.update("""
                update inventory.uom_conversions set default_purchase = false, version = version + 1,
                    last_modified_at = now(), last_modified_by = ?
                where inventory_item_id = ? and default_purchase and id <> ?""", actor, itemId, keep);
    }

    /** Unmark the default shipping unit of the item, except {@code keep}. */
    public void clearDefaultShipping(UUID itemId, UUID keep, String actor) {
        jdbc.update("""
                update inventory.uom_conversions set default_shipping = false, version = version + 1,
                    last_modified_at = now(), last_modified_by = ?
                where inventory_item_id = ? and default_shipping and id <> ?""", actor, itemId, keep);
    }

    /** Hand the default purchase mark back to the base unit when no unit holds it any more. */
    public void defaultToBaseIfNone(UUID itemId, String actor) {
        jdbc.update("""
                update inventory.uom_conversions set default_purchase = true, version = version + 1,
                    last_modified_at = now(), last_modified_by = ?
                where inventory_item_id = ? and is_base and not exists (
                    select 1 from inventory.uom_conversions where inventory_item_id = ? and default_purchase)""",
                actor, itemId, itemId);
    }

    public void insert(UUID id, UUID itemId, UomConversionDraft d, String actor) {
        jdbc.update("""
                insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                    weight_kg, length_cm, width_cm, height_cm, default_purchase, active, default_shipping, created_by)
                values (?, ?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?)""", id, itemId, d.unitOfMeasure(), d.factor(),
                d.weightKg(), d.lengthCm(), d.widthCm(), d.heightCm(), d.defaultPurchase(), d.active(),
                d.defaultShipping(), actor);
    }

    /** False when the version moved on. */
    public boolean update(UUID id, long version, UomConversionDraft d, boolean base, String actor) {
        if (base) {
            return jdbc.update("""
                    update inventory.uom_conversions set default_purchase = ?, default_shipping = ?,
                        version = version + 1, last_modified_at = now(), last_modified_by = ?
                    where id = ? and version = ?""", d.defaultPurchase(), d.defaultShipping(), actor, id,
                    version) == 1;
        }
        return jdbc.update("""
                update inventory.uom_conversions set unit_of_measure = ?, factor = ?, weight_kg = ?, length_cm = ?,
                    width_cm = ?, height_cm = ?, default_purchase = ?, active = ?, default_shipping = ?,
                    version = version + 1, last_modified_at = now(), last_modified_by = ?
                where id = ? and version = ?""", d.unitOfMeasure(), d.factor(), d.weightKg(), d.lengthCm(),
                d.widthCm(), d.heightCm(), d.defaultPurchase(), d.active(), d.defaultShipping(), actor, id,
                version) == 1;
    }

    public boolean delete(UUID id, long version) {
        return jdbc.update("delete from inventory.uom_conversions where id = ? and version = ? and not is_base",
                id, version) == 1;
    }
}
