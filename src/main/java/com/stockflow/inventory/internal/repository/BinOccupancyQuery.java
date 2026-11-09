package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.CartonFit;
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

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
