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

    /** Deletes the items of these SKUs; a foreign key that still points at one refuses the whole statement. */
    public void deleteItems(java.util.Collection<String> skus) {
        if (skus.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(skus.size(), "?"));
        jdbc.update("delete from inventory.inventory_items where sku in (" + placeholders + ")", skus.toArray());
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

    private static InventoryPolicy policy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InventoryPolicy(
                rs.getObject("reorder_point", Integer.class),
                rs.getObject("safety_stock", Integer.class),
                rs.getObject("max_qty", Integer.class),
                RemovalStrategy.valueOf(rs.getString("removal_strategy")),
                TrackingMode.of(rs.getBoolean("lot_tracked"), rs.getBoolean("serial_tracked")),
                rs.getBoolean("expiry_tracked"),
                rs.getObject("max_shelf_life_days", Integer.class));
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
                rs.getBoolean("qc_required"));
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

    /**
     * Saves the policy and retires {@code min_qty}. That column is superseded by {@code safetyStock} and
     * {@code reorderPoint} and read by nothing, but {@code ck_inventory_items_thresholds} still requires
     * {@code max_qty >= min_qty}: left alone, a seeded {@code min_qty} above the new {@code maxQty} would fail this
     * UPDATE at the database with a field-less 409. A policy save therefore sets it NULL.
     */
    public boolean save(String sku, long version, InventoryPolicy p, String actor) {
        return jdbc.update(
                        """
update inventory.inventory_items set reorder_point=?, safety_stock=?, max_qty=?, min_qty=NULL, removal_strategy=?,
    lot_tracked=?, serial_tracked=?, expiry_tracked=?, max_shelf_life_days=?,
    policy_configured=true, version=version+1, last_modified_at=now(), last_modified_by=?
where sku=? and version=?
""",
                        p.reorderPoint(),
                        p.safetyStock(),
                        p.maxQty(),
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

    /**
     * What counts as usable stock, in one place: AVAILABLE and not expired on the business date. Reservations are
     * not subtracted (this is not ATP). {@code usableOnHand}, {@code thresholds} and {@code thresholdPage} all read
     * through it, so the alert evaluator, the reconcile and the dashboard cannot drift apart (plan A8).
     */
    private static final String USABLE =
            " status = 'AVAILABLE' AND (expiry_date IS NULL OR expiry_date >= ?) ";

    public long usableOnHand(String sku, LocalDate today) {
        Long quantity =
                jdbc.queryForObject(
                        "select coalesce(sum(on_hand),0) from inventory.stock_item where sku=? and" + USABLE,
                        Long.class,
                        sku,
                        today);
        return quantity == null ? 0 : quantity;
    }

    /** A SKU's thresholds next to its usable on-hand; the input of every comparison in {@code StockThresholds}. */
    public record ThresholdRow(String sku, long usableOnHand, Integer reorderPoint, Integer safetyStock) {
    }

    /** The row of each of these SKUs that has an inventory item, thresholds set or not. */
    public java.util.List<ThresholdRow> thresholds(java.util.Collection<String> skus, LocalDate today) {
        if (skus == null || skus.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc.query("""
                SELECT i.sku, coalesce(u.qty, 0) AS qty, i.reorder_point, i.safety_stock
                FROM inventory.inventory_items i
                LEFT JOIN (SELECT sku, sum(on_hand) AS qty FROM inventory.stock_item
                           WHERE sku = ANY (?) AND """ + USABLE + """
                           GROUP BY sku) u ON u.sku = i.sku
                WHERE i.sku = ANY (?)""",
                ps -> {
                    java.sql.Array array = ps.getConnection().createArrayOf("varchar", skus.toArray());
                    ps.setArray(1, array);
                    ps.setObject(2, today);
                    ps.setArray(3, array);
                }, (rs, n) -> thresholdRow(rs));
    }

    /**
     * The next {@code limit} SKUs after {@code afterSku}, in SKU order, that have a reorder point or a safety stock,
     * with their usable on-hand: one page of the scan {@code InventoryService.lowStockBatch} and the nightly
     * reconcile walk. Keyset paging, so a page costs the same however far in it is.
     */
    public java.util.List<ThresholdRow> thresholdPage(String afterSku, int limit, LocalDate today) {
        return jdbc.query("""
                WITH page AS (
                    SELECT sku, reorder_point, safety_stock FROM inventory.inventory_items
                    WHERE sku > ? AND (reorder_point IS NOT NULL OR safety_stock IS NOT NULL)
                    ORDER BY sku LIMIT ?)
                SELECT p.sku, coalesce(u.qty, 0) AS qty, p.reorder_point, p.safety_stock
                FROM page p
                LEFT JOIN (SELECT sku, sum(on_hand) AS qty FROM inventory.stock_item
                           WHERE sku IN (SELECT sku FROM page) AND """ + USABLE + """
                           GROUP BY sku) u ON u.sku = p.sku
                ORDER BY p.sku""", (rs, n) -> thresholdRow(rs), afterSku == null ? "" : afterSku, limit, today);
    }

    private static ThresholdRow thresholdRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThresholdRow(rs.getString("sku"), rs.getLong("qty"), rs.getObject("reorder_point", Integer.class),
                rs.getObject("safety_stock", Integer.class));
    }

    /** One SKU with stock that no alert can ever be raised for, because it has no reorder point. */
    public record UnconfiguredRow(String sku, long usableOnHand, Integer safetyStock) {
    }

    /** SKUs that hold usable stock but have no reorder point, in SKU order (the planner's "set a threshold" tab). */
    public java.util.List<UnconfiguredRow> unconfigured(int limit, long offset, LocalDate today) {
        return jdbc.query("""
                SELECT i.sku, u.qty, i.safety_stock FROM inventory.inventory_items i
                JOIN (SELECT sku, sum(on_hand) AS qty FROM inventory.stock_item
                      WHERE """ + USABLE + """
                      GROUP BY sku HAVING sum(on_hand) > 0) u ON u.sku = i.sku
                WHERE i.reorder_point IS NULL
                ORDER BY i.sku LIMIT ? OFFSET ?""",
                (rs, n) -> new UnconfiguredRow(rs.getString("sku"), rs.getLong("qty"),
                        rs.getObject("safety_stock", Integer.class)), today, limit, offset);
    }

    public long countUnconfigured(LocalDate today) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM inventory.inventory_items i
                WHERE i.reorder_point IS NULL AND EXISTS (
                    SELECT 1 FROM inventory.stock_item WHERE sku = i.sku AND """ + USABLE + """
                    GROUP BY sku HAVING sum(on_hand) > 0)""", Long.class, today);
        return count == null ? 0 : count;
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
                    shipping_restriction_note=?, qc_required=?, version=version+1, last_modified_at=now(),
                    last_modified_by=?
                where sku=? and version=?
                """, l.unitOfMeasure(), l.barcode(), l.weightKg(), l.lengthCm(), l.widthCm(), l.heightCm(),
                l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(), l.packageHeightCm(), l.packageCount(),
                l.packSize(), l.storageClass().name(), l.requiresAdultSignature(), l.shippingRestrictionNote(),
                l.qcRequired(), actor, sku, version) == 1;
    }
}
