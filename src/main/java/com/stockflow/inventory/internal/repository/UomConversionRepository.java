package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code inventory.uom_conversions}: the cartons of a catalogue variant, one active (kltn-docs 01 BR-03), keyed by
 * variant since V20261231000100 (owner decision Q1). A SKU's cartons are its variant's, read through
 * {@code inventory_items.variant_id}: asked for a customer's SKU they come back under that SKU, so a caller keyed by
 * SKU needs nothing else. Since V20261215000100 the item has no size or weight of its own: only the carton is measured.
 */
@Repository
public class UomConversionRepository {

    private static final String SELECT = """
            select c.id, i.sku, c.unit_of_measure, c.factor, c.active, c.version, c.weight_kg, c.length_cm, c.width_cm, c.height_cm
            from inventory.inventory_items i join inventory.uom_conversions c on c.variant_id = i.variant_id
            """;

    /** By id alone a carton is no one SKU's - every SKU of its variant shares it - so {@code sku} is null. */
    private static final String SELECT_BY_ID = """
            select c.id, null as sku, c.unit_of_measure, c.factor, c.active, c.version, c.weight_kg, c.length_cm, c.width_cm, c.height_cm
            from inventory.uom_conversions c
            """;

    private static final RowMapper<UomConversion> ROW = (rs, n) -> new UomConversion(
            rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("unit_of_measure"), rs.getInt("factor"),
            rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"),
            rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
            rs.getBoolean("active"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public UomConversionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> itemId(String sku) {
        return jdbc.query("select id from inventory.inventory_items where sku = ?",
                (rs, n) -> rs.getObject("id", UUID.class), sku).stream().findFirst();
    }

    /** The catalogue variant whose cartons the SKU is packed in ({@code inventory_items.variant_id}). */
    public Optional<UUID> variantId(String sku) {
        return jdbc.query("select variant_id from inventory.inventory_items where sku = ?",
                (rs, n) -> rs.getObject("variant_id", UUID.class), sku).stream().findFirst();
    }

    /** Every SKU packed in the variant's cartons, sorted: the SKU locks a change of its carton takes, in order. */
    public List<String> skusOfVariant(UUID variantId) {
        return jdbc.queryForList("select sku from inventory.inventory_items where variant_id = ? order by sku",
                String.class, variantId);
    }

    public long count(String sku) {
        Long count = jdbc.queryForObject("""
                select count(*) from inventory.inventory_items i
                join inventory.uom_conversions c on c.variant_id = i.variant_id where i.sku = ?""", Long.class, sku);
        return count == null ? 0 : count;
    }

    /** By code. */
    public List<UomConversion> page(String sku, int limit, long offset) {
        return jdbc.query(SELECT + " where i.sku = ? order by c.unit_of_measure limit ? offset ?",
                ROW, sku, limit, offset);
    }

    public Optional<UomConversion> find(String sku, UUID id) {
        return jdbc.query(SELECT + " where i.sku = ? and c.id = ?", ROW, sku, id).stream().findFirst();
    }

    /**
     * The units with these ids, whatever their variant, with {@code sku} null: a carton is shared by every SKU of its
     * variant, so the SKU of a stock package is its own item's, never its unit's.
     */
    public List<UomConversion> findAll(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT_BY_ID + " where c.id = any (?)", ps ->
                ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())), ROW);
    }

    public List<UomConversion> find(String sku, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT + " where i.sku = ? and c.id = any (?)", ps -> {
            ps.setString(1, sku);
            ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
        }, ROW);
    }

    /** The units of each of these SKUs by code; a SKU without units is absent. */
    public Map<String, List<UomConversion>> bySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }
        List<UomConversion> rows = jdbc.query(
                SELECT + " where i.sku = any (?) order by i.sku, c.unit_of_measure", ps ->
                        ps.setArray(1, ps.getConnection().createArrayOf("text", skus.toArray())), ROW);
        Map<String, List<UomConversion>> out = new HashMap<>();
        rows.forEach(u -> out.computeIfAbsent(u.sku(), k -> new ArrayList<>()).add(u));
        return out;
    }

    /**
     * Whether the code is taken on the variant: by another of its cartons, or by the unit the pieces of its SKUs are
     * counted in ({@code inventory_items.unit_of_measure}), which a carton must not be mistaken for
     * ({@code tg_uom_conversions_unit_code}).
     */
    public boolean unitTaken(UUID variantId, String unitOfMeasure, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from inventory.uom_conversions
                    where variant_id = ? and unit_of_measure = ? and id is distinct from ?)
                    or exists(select 1 from inventory.inventory_items where variant_id = ? and unit_of_measure = ?)""",
                Boolean.class, variantId, unitOfMeasure, except, variantId, unitOfMeasure));
    }

    /** The code of the variant's active carton other than {@code except}, if any (01 BR-03: there is at most one). */
    public Optional<String> activeCartonOf(UUID variantId, UUID except) {
        return jdbc.query("""
                select unit_of_measure from inventory.uom_conversions
                where variant_id = ? and active and id is distinct from ?""",
                (rs, n) -> rs.getString("unit_of_measure"), variantId, except).stream().findFirst();
    }

    /**
     * Whether a document counts in this unit (inventory.unit_in_use, V20261020000600), or is being written
     * in it right now: a document's trigger holds the unit's advisory lock shared until it commits, and an
     * uncommitted document is invisible to unit_in_use. Trying the lock exclusively answers both, and the
     * lock is then held, so tg_uom_conversions_locked_in_use - which tries the same key in this
     * transaction - passes on the change that follows.
     *
     * <p>Two statements, lock first: in READ COMMITTED a statement reads the snapshot taken when it
     * started, so unit_in_use asked in the statement that takes the lock would miss a document that
     * committed, letting go of its lock, between that snapshot and the lock being granted. The trigger
     * would still refuse the change, but as a generic 409.</p>
     */
    public boolean inUse(UUID id) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(inventory.unit_lock_key(?))", Boolean.class, id))) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("select inventory.unit_in_use(?)", Boolean.class, id));
    }

    public void insert(UUID id, UUID variantId, UomConversionDraft d, String actor) {
        jdbc.update("""
                insert into inventory.uom_conversions (id, variant_id, unit_of_measure, factor,
                    weight_kg, length_cm, width_cm, height_cm, active, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, variantId, d.unitOfMeasure(), d.factor(),
                d.weightKg(), d.lengthCm(), d.widthCm(), d.heightCm(), d.active(), actor);
    }

    /** False when the version moved on. */
    public boolean update(UUID id, long version, UomConversionDraft d, String actor) {
        return jdbc.update("""
                update inventory.uom_conversions set unit_of_measure = ?, factor = ?, weight_kg = ?, length_cm = ?,
                    width_cm = ?, height_cm = ?, active = ?,
                    version = version + 1, last_modified_at = now(), last_modified_by = ?
                where id = ? and version = ?""", d.unitOfMeasure(), d.factor(), d.weightKg(), d.lengthCm(),
                d.widthCm(), d.heightCm(), d.active(), actor, id, version) == 1;
    }

    public boolean delete(UUID id, long version) {
        return jdbc.update("delete from inventory.uom_conversions where id = ? and version = ?", id, version) == 1;
    }
}
