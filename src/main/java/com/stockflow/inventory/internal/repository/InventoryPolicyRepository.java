package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryControlItem;
import com.stockflow.inventory.api.InventoryItemLogistics;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Writes only inventory schema. The same advisory lock is taken by the stock-row trigger. */
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

    /**
     * Whether the database refuses to raise an existing stock layer of this SKU ({@code inventory.enforce_sku_policy},
     * V20261017000450): a configured FIFO item, whose new units must be a new receipt layer or FIFO loses their age.
     * An unconfigured item is not checked by the trigger, whatever its strategy says.
     */
    public boolean replenishesByNewLayer(String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from inventory.inventory_items"
                + " where sku = ? and policy_configured and removal_strategy = 'FIFO')", Boolean.class, sku));
    }

    public boolean hasActiveCount(String sku) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        select exists(select 1 from inventory.cycle_count_line l
                        join inventory.cycle_count c on c.id=l.cycle_count_id
                        where c.status in ('PLANNED','IN_PROGRESS') and l.sku=?)
                        """,
                        Boolean.class,
                        sku));
    }

    public Optional<InventoryControlItem> findItem(String sku) {
        return jdbc.query("select * from inventory.inventory_items where sku = ?", (rs, n) ->
                new InventoryControlItem(rs.getString("sku"), rs.getString("unit_of_measure"), rs.getLong("version"),
                        policy(rs)), sku).stream().findFirst();
    }

    /** One inventory item, both groups and the version, as they stood together. */
    public record ItemRow(java.util.UUID id, String sku, long version, ItemLogistics logistics,
                          InventoryPolicy policy) {
    }

    /**
     * The whole item in one SELECT. Reading the logistics, the policy and the version separately lets a concurrent
     * save land between the reads, and the result would label one save's values with another's version.
     */
    public Optional<ItemRow> findById(java.util.UUID id) {
        return jdbc.query("select * from inventory.inventory_items where id = ?", (rs, n) ->
                new ItemRow(rs.getObject("id", java.util.UUID.class), rs.getString("sku"), rs.getLong("version"),
                        logistics(rs), policy(rs)), id).stream().findFirst();
    }

    /** The items with these SKUs, in one SELECT; a SKU without an item is simply absent. */
    public List<ItemRow> findBySkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select * from inventory.inventory_items where sku = any (?)", ps ->
                ps.setArray(1, ps.getConnection().createArrayOf("text", skus.toArray())), (rs, n) ->
                new ItemRow(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getLong("version"),
                        logistics(rs), policy(rs)));
    }

    private static InventoryPolicy policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryPolicy(
                RemovalStrategy.valueOf(rs.getString("removal_strategy")),
                TrackingMode.of(rs.getBoolean("lot_tracked"), rs.getBoolean("serial_tracked")));
    }

    private static ItemLogistics logistics(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemLogistics(
                rs.getString("unit_of_measure"), rs.getString("barcode"),
                rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"),
                rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                rs.getBigDecimal("package_weight_kg"), rs.getBigDecimal("package_length_cm"),
                rs.getBigDecimal("package_width_cm"), rs.getBigDecimal("package_height_cm"),
                rs.getInt("package_count"), rs.getInt("pack_size"),
                StorageClass.valueOf(rs.getString("storage_class")),
                rs.getBoolean("requires_adult_signature"), rs.getString("shipping_restriction_note"),
                rs.getBoolean("rotatable"));
    }

    /** The SKU of the item with this id; empty when there is none. */
    public Optional<String> findSku(java.util.UUID id) {
        return jdbc.queryForList("select sku from inventory.inventory_items where id = ?", String.class, id)
                .stream()
                .findFirst();
    }

    public Optional<InventoryPolicy> find(String sku) {
        return findItem(sku).map(InventoryControlItem::policy);
    }

    /**
     * Whether stock of {@code sku} breaks {@code p}: the twin of {@code inventory.enforce_sku_policy}, which refuses
     * the same rows at write time. A lot conflicts with no policy (V20261111000110): every unit carries one (kltn-docs
     * 20 BR-22, lot {@code UNTRACED} for what had none), so only a lot-tracked SKU's stock without one does.
     */
    public boolean incompatible(String sku, InventoryPolicy p) {
        Long count =
                jdbc.queryForObject(
                        """
select count(*) from inventory.stock_item where sku = ? and (on_hand > 0 or reserved > 0)
and ((? and nullif(trim(lot_number), '') is null)
  or (? and (nullif(trim(serial_number), '') is null or on_hand > 1))
  or (not ? and serial_number is not null)
  or (? = 'FIFO' and received_at is null))
""",
                        Long.class,
                        sku,
                        p.trackingMode().lotTracked(),
                        p.trackingMode().serialTracked(),
                        p.trackingMode().serialTracked(),
                        p.removalStrategy().name());
        return count != null && count > 0;
    }

    /**
     * Saves the policy. There are no thresholds and no expiry settings any more: the module raises no stock alert and
     * tracks no expiry (kltn-docs 20 BR-13, 01 BR-17; team plan 5-1).
     */
    public boolean save(String sku, long version, InventoryPolicy p, String actor) {
        return jdbc.update(
                        """
update inventory.inventory_items set removal_strategy=?, lot_tracked=?, serial_tracked=?,
    policy_configured=true, version=version+1, last_modified_at=now(), last_modified_by=?
where sku=? and version=?
""",
                        p.removalStrategy().name(),
                        p.trackingMode().lotTracked(),
                        p.trackingMode().serialTracked(),
                        actor,
                        sku,
                        version)
                == 1;
    }

    public Optional<InventoryItemLogistics> findLogistics(String sku) {
        return jdbc.query("select * from inventory.inventory_items where sku = ?", (rs, n) ->
                new InventoryItemLogistics(rs.getString("sku"), rs.getLong("version"), logistics(rs)), sku)
                .stream().findFirst();
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
                    package_height_cm=?, package_count=?, pack_size=?, storage_class=?, requires_adult_signature=?,
                    shipping_restriction_note=?, rotatable=?, version=version+1, last_modified_at=now(),
                    last_modified_by=?
                where sku=? and version=?
                """, l.unitOfMeasure(), l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.storageClass().name(), l.requiresAdultSignature(), l.shippingRestrictionNote(),
                l.rotatable(), actor, sku, version) == 1;
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
