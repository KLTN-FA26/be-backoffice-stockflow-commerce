package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

/** Writes only inventory schema. The same advisory lock is taken by the stock-row trigger. */
@Repository
public class InventoryPolicyRepository {
    private final JdbcTemplate jdbc;
    public InventoryPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void lock(String sku) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 701))", rs -> { }, sku);
    }
    public void lockReservationStock(java.util.Set<String> skus) {
        if(skus.isEmpty())return;
        skus.stream().sorted().forEach(this::lock);
        String placeholders=String.join(",",java.util.Collections.nCopies(skus.size(),"?"));
        jdbc.query("select id from inventory.stock_item where sku in ("+placeholders+") order by id for update",r -> {},skus.toArray());
    }
    public boolean hasActiveCount(String sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from inventory.cycle_count_line l join inventory.stock_item s on s.id=l.stock_id
                where l.active and s.sku=?)
                """,Boolean.class,sku));
    }
    public Optional<InventoryPolicy> find(String sku) {
        return jdbc.query("select * from inventory.sku_policy where sku = ?", (rs, n) ->
                new InventoryPolicy(rs.getObject("reorder_point", Integer.class),
                        rs.getObject("safety_stock", Integer.class), RemovalStrategy.valueOf(rs.getString("removal_strategy")),
                        TrackingMode.valueOf(rs.getString("tracking_mode")), rs.getBoolean("expiry_tracked"),
                        rs.getObject("max_shelf_life_days", Integer.class)), sku).stream().findFirst();
    }
    public boolean incompatible(String sku, InventoryPolicy p) {
        Long count = jdbc.queryForObject("""
                select count(*) from inventory.stock_item where sku = ? and (on_hand > 0 or reserved > 0)
                and ((? = 'NONE' and (lot_number is not null or serial_number is not null or expiry_date is not null))
                  or (? = 'LOT' and (nullif(trim(lot_number), '') is null or serial_number is not null))
                  or (? = 'SERIAL' and (nullif(trim(serial_number), '') is null or on_hand > 1))
                  or (? and expiry_date is null) or (not ? and expiry_date is not null)
                  or (? = 'FIFO' and received_at is null))
                """, Long.class, sku, p.trackingMode().name(), p.trackingMode().name(), p.trackingMode().name(),
                p.expiryTracked(), p.expiryTracked(), p.removalStrategy().name());
        return count != null && count > 0;
    }
    public void save(String sku, InventoryPolicy p) {
        jdbc.update("""
                insert into inventory.sku_policy(sku, reorder_point, safety_stock, removal_strategy,
                    tracking_mode, expiry_tracked, max_shelf_life_days)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (sku) do update set reorder_point=excluded.reorder_point,
                    safety_stock=excluded.safety_stock, removal_strategy=excluded.removal_strategy,
                    tracking_mode=excluded.tracking_mode, expiry_tracked=excluded.expiry_tracked,
                    max_shelf_life_days=excluded.max_shelf_life_days
                """, sku, p.reorderPoint(), p.safetyStock(), p.removalStrategy().name(),
                p.trackingMode().name(), p.expiryTracked(), p.maxShelfLifeDays());
    }
    public long usableOnHand(String sku, LocalDate today) {
        Long quantity = jdbc.queryForObject("""
                select coalesce(sum(on_hand),0) from inventory.stock_item where sku=?
                and status='AVAILABLE' and (expiry_date is null or expiry_date >= ?)
                """, Long.class, sku, today);
        return quantity == null ? 0 : quantity;
    }
}
