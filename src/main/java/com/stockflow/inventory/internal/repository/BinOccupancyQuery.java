package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.CartonFit;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the given bins hold right now, and what a carton takes of a bin, for putting it away (SCRUM-92; kltn-docs
 * 05 BR-03, 06 BR-19). Grouped reads of the inventory schema only: sizes and weights come from the carton units and
 * the item master of the same schema, never from another module's tables.
 *
 * <p>A bin's load is every carton on its rows - any SKU, any status - because the shelf is as full of a quarantined
 * or held carton as of a sellable one. A carton takes its carton unit's length x width x height and weighs what that
 * unit says a full carton weighs (05 BR-03, decided 2026-11-05); a unit without a recorded weight falls back to the
 * item's unit weight times the units, and without either the carton weighs nothing here, so the rule cannot be
 * broken by a number nobody knows. Units outside any carton (history, before V20261111000110) take no volume and
 * weigh their unit weight.</p>
 */
@Repository
public class BinOccupancyQuery {

    /**
     * The seed of the bin lock, so it cannot collide with the other advisory locks keyed by a string: 701 SKU, 702
     * stock alerts, 703 cycle-count slots and UoM conversions. Public so a test can take the lock a confirmation takes.
     */
    public static final int BIN_LOCK_SEED = 704;

    private final JdbcTemplate jdbc;

    public BinOccupancyQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A transaction-scoped advisory lock on a bin, for the putaways into it. Occupancy is bin-wide across SKUs, so
     * the SKU locks cannot serialise two putaways of different SKUs into one bin; this one does. Only putaway checks
     * a bin's volume and load, so it serialises putaways, not other moves.
     */
    public void lockBin(String locationCode) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, " + BIN_LOCK_SEED + "))", rs -> {},
                "putaway-bin:" + locationCode);
    }

    /** {@link #occupancyOf(Collection, String, String, UUID)} counting every carton. */
    public Map<String, Occupancy> occupancyOf(Collection<String> locationCodes, String sku, String lotNumber) {
        return occupancyOf(locationCodes, sku, lotNumber, null);
    }

    /**
     * The occupancy of each of these bins; a bin holding nothing is absent, which the ranker reads as empty.
     * {@code holdsSku} / {@code holdsLot} refer to the SKU (and lot number) being put away.
     *
     * @param leaveOut a carton not to count, or null: the one being put away, when somebody already moved it into
     *                 the bin by hand. Counted, a carton that fills a bin exactly would not fit the bin it stands in
     */
    public Map<String, Occupancy> occupancyOf(Collection<String> locationCodes, String sku, String lotNumber,
                                              UUID leaveOut) {
        if (locationCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Occupancy> out = new HashMap<>();
        jdbc.query("""
                with r as (
                    select s.location_code, s.stock_package_id, s.sku, sum(s.on_hand) as on_hand,
                           bool_or(s.lot_number is not distinct from cast(? as text)) as same_lot
                    from inventory.stock_item s
                    where s.location_code = any (?)
                      and s.on_hand > 0
                      and (cast(? as uuid) is null or s.stock_package_id is distinct from cast(? as uuid))
                    group by s.location_code, s.stock_package_id, s.sku)
                select r.location_code,
                       sum(r.on_hand) as units,
                       sum(case when r.stock_package_id is null then 0
                                else coalesce(u.length_cm * u.width_cm * u.height_cm / 1000000, 0) end) as volume_m3,
                       sum(case when r.stock_package_id is not null and u.weight_kg is not null then u.weight_kg
                                else r.on_hand * coalesce(i.weight_kg, 0) end) as weight_kg,
                       coalesce(bool_or(r.sku = ?), false) as holds_sku,
                       coalesce(bool_or(r.sku = ? and r.same_lot and cast(? as text) is not null), false) as holds_lot
                from r
                left join inventory.stock_package p on p.id = r.stock_package_id
                left join inventory.uom_conversions u on u.id = p.uom_conversion_id
                left join inventory.inventory_items i on i.sku = r.sku
                group by r.location_code""", ps -> {
            ps.setString(1, lotNumber);
            ps.setArray(2, ps.getConnection().createArrayOf("text", locationCodes.toArray()));
            ps.setObject(3, leaveOut, Types.OTHER);
            ps.setObject(4, leaveOut, Types.OTHER);
            ps.setString(5, sku);
            ps.setString(6, sku);
            ps.setString(7, lotNumber);
        }, rs -> {
            out.put(rs.getString("location_code"), new Occupancy(rs.getInt("units"),
                    orZero(rs.getBigDecimal("volume_m3")), orZero(rs.getBigDecimal("weight_kg")),
                    rs.getBoolean("holds_sku"), rs.getBoolean("holds_lot")));
        });
        return out;
    }

    /**
     * What each of these cartons takes of a bin: its carton unit's size, whether its item may be turned on the
     * horizontal ({@code rotatable}, docs 01) and its full weight - the unit's, else the item's unit weight times its
     * standard count. A carton that does not exist is absent.
     */
    public Map<UUID, Need> needsOf(Collection<UUID> cartonIds) {
        if (cartonIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Need> out = new HashMap<>();
        jdbc.query("""
                select p.id, u.length_cm, u.width_cm, u.height_cm, i.rotatable,
                       coalesce(u.weight_kg, i.weight_kg * p.nominal_qty) as weight_kg
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                join inventory.inventory_items i on i.id = p.inventory_item_id
                where p.id = any (?)""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", cartonIds.toArray())),
                rs -> {
                    out.put(rs.getObject("id", UUID.class), new Need(new CartonFit.Carton(rs.getBigDecimal("length_cm"),
                            rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"), rs.getBoolean("rotatable")),
                            rs.getBigDecimal("weight_kg")));
                });
        return out;
    }

    /**
     * The volume and full weight of those of {@code cartonIds} that hold units of {@code lotNumber}: the rest of a lot
     * still to put away, which empty bins are ranked by (05 §4.2). Unmeasured cartons add nothing.
     */
    public PutawayRanker.Lot lotOf(Collection<UUID> cartonIds, String lotNumber) {
        if (cartonIds.isEmpty()) {
            return new PutawayRanker.Lot(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return jdbc.query("""
                select coalesce(sum(u.length_cm * u.width_cm * u.height_cm / 1000000), 0) as volume_m3,
                       coalesce(sum(coalesce(u.weight_kg, i.weight_kg * p.nominal_qty)), 0) as weight_kg
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                join inventory.inventory_items i on i.id = p.inventory_item_id
                where p.id = any (?)
                  and exists (select 1 from inventory.stock_item s where s.stock_package_id = p.id
                                 and s.on_hand > 0 and s.lot_number = ?)""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("uuid", cartonIds.toArray()));
            ps.setString(2, lotNumber);
        }, rs -> {
            rs.next();
            return new PutawayRanker.Lot(orZero(rs.getBigDecimal("volume_m3")), orZero(rs.getBigDecimal("weight_kg")));
        });
    }

    /**
     * What a carton adds to the bin it goes into: its SKU and lot (for grouping), its units, volume and full weight.
     * The same figures {@link #occupancyOf} counts for a carton already standing there.
     */
    public record CartonLoad(String sku, String lotNumber, int units, BigDecimal volumeM3, BigDecimal weightKg) {
    }

    /** The load of each of these cartons, in one read; a carton that does not exist is absent. */
    public Map<UUID, CartonLoad> loadsOf(Collection<UUID> cartonIds) {
        if (cartonIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CartonLoad> out = new HashMap<>();
        jdbc.query("""
                select p.id, i.sku,
                       (select min(s.lot_number) from inventory.stock_item s
                         where s.stock_package_id = p.id and s.on_hand > 0) as lot_number,
                       coalesce(p.received_qty, p.nominal_qty) as units,
                       coalesce(u.length_cm * u.width_cm * u.height_cm / 1000000, 0) as volume_m3,
                       coalesce(u.weight_kg, i.weight_kg * p.nominal_qty, 0) as weight_kg
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                join inventory.inventory_items i on i.id = p.inventory_item_id
                where p.id = any (?)""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", cartonIds.toArray())),
                rs -> {
                    out.put(rs.getObject("id", UUID.class), new CartonLoad(rs.getString("sku"),
                            rs.getString("lot_number"), rs.getInt("units"), orZero(rs.getBigDecimal("volume_m3")),
                            orZero(rs.getBigDecimal("weight_kg"))));
                });
        return out;
    }

    /** A carton standing at {@code locationCode}, in carton unit {@code unitId}, with what it measures. */
    public record CartonIn(String locationCode, UUID cartonId, UUID unitId, String lpn, CartonFit.Carton carton) {
    }

    /** The cartons holding units at these locations, in LPN order; an empty carton (picked, closed) is not there. */
    public java.util.List<CartonIn> cartonsIn(Collection<String> locationCodes) {
        if (locationCodes.isEmpty()) {
            return java.util.List.of();
        }
        return jdbc.query("""
                select p.location_code, p.id, p.uom_conversion_id, p.lpn, u.length_cm, u.width_cm, u.height_cm,
                       i.rotatable
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                join inventory.inventory_items i on i.id = p.inventory_item_id
                where p.location_code = any (?)
                  and exists (select 1 from inventory.stock_item s where s.stock_package_id = p.id and s.on_hand > 0)
                order by p.lpn""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", locationCodes.toArray())),
                (rs, n) -> new CartonIn(rs.getString("location_code"), rs.getObject("id", UUID.class),
                        rs.getObject("uom_conversion_id", UUID.class), rs.getString("lpn"),
                        new CartonFit.Carton(rs.getBigDecimal("length_cm"), rs.getBigDecimal("width_cm"),
                                rs.getBigDecimal("height_cm"), rs.getBoolean("rotatable"))));
    }

    /** A carton standing at {@code locationCode}, named by its LPN. */
    public record CartonAt(String locationCode, String lpn) {
    }

    /**
     * The cartons that stand at these locations, by their own status: labelled on a receipt not yet confirmed
     * ({@code RECEIVING}) or in stock ({@code IN_STOCK}). Unlike {@link #cartonsIn}, a carton holding no units yet - one
     * just labelled on a draft receipt - counts: it is on the floor all the same (kltn-docs 06 BR-04).
     *
     * @return how many there are, and the first {@code limit} in location code then LPN order
     */
    public Standing standingAt(Collection<String> locationCodes, int limit) {
        if (locationCodes.isEmpty()) {
            return new Standing(0, java.util.List.of());
        }
        java.util.List<CartonAt> first = new java.util.ArrayList<>();
        long[] total = {0};
        jdbc.query("""
                select p.location_code, p.lpn, count(*) over () as total
                from inventory.stock_package p
                where p.location_code = any (?)
                  and p.status in ('RECEIVING', 'IN_STOCK')
                order by p.location_code, p.lpn
                limit ?""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", locationCodes.toArray()));
            ps.setInt(2, Math.max(limit, 1));
        }, rs -> {
            total[0] = rs.getLong("total");
            if (first.size() < limit) {
                first.add(new CartonAt(rs.getString("location_code"), rs.getString("lpn")));
            }
        });
        return new Standing(total[0], first);
    }

    /** @param first at most the limit asked for */
    public record Standing(long total, java.util.List<CartonAt> first) {
    }

    /** Where cartons of this carton unit holding units stand, by location code. */
    public java.util.List<String> locationsOfUnit(UUID unitId) {
        return jdbc.queryForList("""
                select distinct p.location_code from inventory.stock_package p
                where p.uom_conversion_id = ?
                  and exists (select 1 from inventory.stock_item s where s.stock_package_id = p.id and s.on_hand > 0)
                order by 1""", String.class, unitId);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
