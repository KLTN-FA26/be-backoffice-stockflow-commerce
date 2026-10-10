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

import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

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

    public void lockReservationStock(Set<String> skus) {
        if (skus.isEmpty()) return;
        skus.stream().sorted().forEach(this::lock);
        String placeholders = String.join(",", Collections.nCopies(skus.size(), "?"));
        jdbc.query(
                "select id from inventory.stock_item where sku in ("
                        + placeholders
                        + ") order by id for update",
                r -> {},
                skus.toArray());
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
        return jdbc
                .query(
                        "select * from inventory.inventory_items where sku = ?",
                        (rs, n) ->
                                new InventoryControlItem(
                                        rs.getString("sku"),
                                        rs.getString("unit_of_measure"),
                                        rs.getLong("version"),
                                        new InventoryPolicy(
                                                rs.getObject("reorder_point", Integer.class),
                                                rs.getObject("safety_stock", Integer.class),
                                                RemovalStrategy.valueOf(
                                                        rs.getString("removal_strategy")),
                                                TrackingMode.of(
                                                        rs.getBoolean("lot_tracked"),
                                                        rs.getBoolean("serial_tracked")),
                                                rs.getBoolean("expiry_tracked"),
                                                rs.getObject(
                                                        "max_shelf_life_days", Integer.class))),
                        sku)
                .stream()
                .findFirst();
    }

    public Optional<InventoryPolicy> find(String sku) {
        return findItem(sku).map(InventoryControlItem::policy);
    }

    public boolean incompatible(String sku, InventoryPolicy p) {
        Long count =
                jdbc.queryForObject(
                        """
select count(*) from inventory.stock_item where sku = ? and (on_hand > 0 or reserved > 0)
and ((? and nullif(trim(lot_number), '') is null)
  or (not ? and lot_number is not null)
  or (? and (nullif(trim(serial_number), '') is null or on_hand > 1))
  or (not ? and serial_number is not null)
  or (? and expiry_date is null) or (not ? and expiry_date is not null)
  or (? = 'FIFO' and received_at is null)
  or (cast(? as integer) is not null and
      (received_at is null or expiry_date >
          (received_at at time zone 'Asia/Ho_Chi_Minh')::date + cast(? as integer))))
""",
                        Long.class,
                        sku,
                        p.trackingMode().lotTracked(),
                        p.trackingMode().lotTracked(),
                        p.trackingMode().serialTracked(),
                        p.trackingMode().serialTracked(),
                        p.expiryTracked(),
                        p.expiryTracked(),
                        p.removalStrategy().name(),
                        p.maxShelfLifeDays(),
                        p.maxShelfLifeDays());
        return count != null && count > 0;
    }

    public boolean save(String sku, long version, InventoryPolicy p, String actor) {
        return jdbc.update(
                        """
update inventory.inventory_items set reorder_point=?, safety_stock=?, removal_strategy=?,
    lot_tracked=?, serial_tracked=?, expiry_tracked=?, max_shelf_life_days=?,
    policy_configured=true, version=version+1, last_modified_at=now(), last_modified_by=?
where sku=? and version=?
""",
                        p.reorderPoint(),
                        p.safetyStock(),
                        p.removalStrategy().name(),
                        p.trackingMode().lotTracked(),
                        p.trackingMode().serialTracked(),
                        p.expiryTracked(),
                        p.maxShelfLifeDays(),
                        actor,
                        sku,
                        version)
                == 1;
    }

    public long usableOnHand(String sku, LocalDate today) {
        Long quantity =
                jdbc.queryForObject(
                        """
                        select coalesce(sum(on_hand),0) from inventory.stock_item where sku=?
                        and status='AVAILABLE' and (expiry_date is null or expiry_date >= ?)
                        """,
                        Long.class,
                        sku,
                        today);
        return quantity == null ? 0 : quantity;
    }

    public Optional<InventoryItemLogistics> findLogistics(String sku) {
        return jdbc.query("select * from inventory.inventory_items where sku = ?", (rs, n) ->
                new InventoryItemLogistics(rs.getString("sku"), rs.getLong("version"), new ItemLogistics(
                        rs.getString("unit_of_measure"), rs.getString("barcode"),
                        rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"),
                        rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                        rs.getBigDecimal("package_weight_kg"), rs.getBigDecimal("package_length_cm"),
                        rs.getBigDecimal("package_width_cm"), rs.getBigDecimal("package_height_cm"),
                        rs.getInt("package_count"), rs.getInt("pack_size"),
                        StorageClass.valueOf(rs.getString("storage_class")),
                        rs.getBoolean("requires_adult_signature"), rs.getString("shipping_restriction_note"),
                        rs.getBoolean("qc_required"))), sku).stream().findFirst();
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
                    shipping_restriction_note=?, qc_required=?, version=version+1, last_modified_at=now(),
                    last_modified_by=?
                where sku=? and version=?
                """, l.unitOfMeasure(), l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.storageClass().name(), l.requiresAdultSignature(), l.shippingRestrictionNote(),
                l.qcRequired(), actor, sku, version) == 1;
    }
}
