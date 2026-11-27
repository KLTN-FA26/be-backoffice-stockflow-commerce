package com.stockflow.inventory.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grouped reads of the inventory schema for the warehouse capacity board (kltn-docs 06 §4.6; BE1). Sizes come from the
 * carton unit of each carton ({@code stock_package.uom_conversion_id}), as {@link BinOccupancyQuery} measures what a bin
 * holds: one measure for "what is there" on the board and at putaway.
 *
 * <p>A warehouse is a location-code prefix: {@code <prefix>-%}. A prefix is {@code [A-Z0-9]} (06 BR-10), so it never
 * carries a LIKE wildcard to escape.</p>
 */
@Repository
public class WarehouseOccupancyQuery {

    /** What stands at one location: its cartons, those without a size, their volume and weight. */
    public record AtLocation(String locationCode, int cartons, int cartonsWithoutSize, BigDecimal volumeM3,
                             BigDecimal weightKg) {
    }

    private final JdbcTemplate jdbc;

    public WarehouseOccupancyQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every carton IN_STOCK at a location of the warehouse, by location; a location with none is absent. */
    public List<AtLocation> inStockByLocation(String warehousePrefix) {
        return jdbc.query("""
                select p.location_code,
                       count(*) as cartons,
                       count(*) filter (where u.length_cm is null or u.width_cm is null or u.height_cm is null)
                           as without_size,
                       coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0) as volume_m3,
                       coalesce(sum(u.weight_kg), 0) as weight_kg
                  from inventory.stock_package p
                  join inventory.uom_conversions u on u.id = p.uom_conversion_id
                 where p.status = 'IN_STOCK'
                   and p.location_code like ?
                 group by p.location_code
                 order by p.location_code""",
                (rs, n) -> new AtLocation(rs.getString("location_code"), rs.getInt("cartons"),
                        rs.getInt("without_size"), m3(rs.getBigDecimal("volume_m3")), rs.getBigDecimal("weight_kg")),
                warehousePrefix + "-%");
    }

    /** {@link AtLocation} with the SKUs and units in the cartons: a bin's load for the location list (#456). */
    public record LoadAt(String locationCode, int cartons, int skus, int units, int cartonsWithoutSize,
                         BigDecimal volumeM3, BigDecimal weightKg) {
    }

    /**
     * Every carton IN_STOCK at a location of the warehouse, by location, as {@link #inStockByLocation} counts them, with
     * how many SKUs and units they hold. One statement for the warehouse.
     */
    public List<LoadAt> loadsByLocation(String warehousePrefix) {
        return jdbc.query("""
                select p.location_code,
                       count(*) as cartons,
                       count(distinct p.inventory_item_id) as skus,
                       coalesce(sum(p.nominal_qty), 0) as units,
                       count(*) filter (where u.length_cm is null or u.width_cm is null or u.height_cm is null)
                           as without_size,
                       coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0) as volume_m3,
                       coalesce(sum(u.weight_kg), 0) as weight_kg
                  from inventory.stock_package p
                  join inventory.uom_conversions u on u.id = p.uom_conversion_id
                 where p.status = 'IN_STOCK'
                   and p.location_code like ?
                 group by p.location_code
                 order by p.location_code""",
                (rs, n) -> new LoadAt(rs.getString("location_code"), rs.getInt("cartons"), rs.getInt("skus"),
                        rs.getInt("units"), rs.getInt("without_size"), m3(rs.getBigDecimal("volume_m3")),
                        rs.getBigDecimal("weight_kg")),
                warehousePrefix + "-%");
    }

    /** The locations of the warehouse where a cycle count still open (PLANNED, IN_PROGRESS) counts a slot. */
    public java.util.Set<String> locationsUnderOpenCount(String warehousePrefix) {
        return new java.util.HashSet<>(jdbc.queryForList("""
                select distinct l.location_code
                  from inventory.cycle_count_line l
                  join inventory.cycle_count c on c.id = l.cycle_count_id
                 where c.status in ('PLANNED', 'IN_PROGRESS')
                   and l.location_code like ?""", String.class, warehousePrefix + "-%"));
    }

    /**
     * The units of each SKU held at the warehouse by a hold of its own - {@code HELD}, on no carton yet, taken at
     * this warehouse (4B-7: {@code warehouse_code}). A hold of before 4B-7 names no warehouse and is not counted.
     */
    public Map<String, Long> skuHoldUnits(String warehousePrefix) {
        Map<String, Long> units = new LinkedHashMap<>();
        jdbc.query("""
                select r.sku, sum(r.cartons * r.units_per_carton) as units
                  from inventory.stock_reservation r
                 where r.status = 'HELD' and r.stock_package_id is null and r.warehouse_code = ?
                 group by r.sku""",
                rs -> {
                    units.put(rs.getString("sku"), rs.getLong("units"));
                }, warehousePrefix);
        return units;
    }

    /**
     * The volume of the cartons allocated at the warehouse and not yet consumed: the cartons a live allocation names,
     * each of which has at most one ({@code uk_stock_reservation_live_carton}). A carton without a size adds nothing.
     */
    public BigDecimal allocatedVolumeM3(String warehousePrefix) {
        return m3(jdbc.queryForObject("""
                select coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0)
                  from inventory.stock_reservation r
                  join inventory.stock_package p on p.id = r.stock_package_id
                  join inventory.uom_conversions u on u.id = p.uom_conversion_id
                 where r.status = 'HELD'
                   and p.location_code like ?""",
                BigDecimal.class, warehousePrefix + "-%"));
    }

    private static BigDecimal m3(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(6) : value.setScale(6, RoundingMode.HALF_UP);
    }
}
