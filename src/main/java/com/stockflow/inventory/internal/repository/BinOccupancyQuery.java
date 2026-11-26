package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.CartonFit;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.PutawayRanker.Need;
import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the given bins hold right now, and what a carton takes of a bin, for putting it away (SCRUM-92; kltn-docs
 * 05 BR-03, 06 BR-19) and for suggesting bins to a move of a group of cartons (issue #412; 11 §4.1, docs#46), which
 * judges room by putaway's rule and so must read it the same way. Grouped reads of the inventory schema only: sizes and
 * weights come from the carton units of the same schema, never from another module's tables; whether a carton may be
 * turned is its catalogue variant's, asked once per read through {@link VariantRotations} (owner decision Q9).
 *
 * <p>A bin's load is every carton on its rows - any SKU, any status - because the shelf is as full of a quarantined
 * or held carton as of a sellable one. A carton takes its carton unit's length x width x height and weighs what that
 * unit says a full carton weighs (05 BR-03, decided 2026-11-05); a unit without a recorded weight weighs nothing here,
 * so the rule cannot be broken by a number nobody knows. The item has no weight of its own to stand in
 * (V20261215000100, kltn-docs 01 BR-03). Units outside any carton (history, before V20261111000110) take no volume
 * and no weight.</p>
 */
@Repository
public class BinOccupancyQuery {

    /**
     * The seed of the bin lock, so it cannot collide with the other advisory locks keyed by a string: 701 SKU, 702
     * stock alerts, 703 cycle-count slots and UoM conversions. Public so a test can take the lock a confirmation takes.
     */
    public static final int BIN_LOCK_SEED = 704;

    private final JdbcTemplate jdbc;
    private final VariantRotations rotations;

    public BinOccupancyQuery(JdbcTemplate jdbc, VariantRotations rotations) {
        this.jdbc = jdbc;
        this.rotations = rotations;
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
     * {@code holdsSku} / {@code holdsLot} refer to the SKU (and lot number) being put away: the group of one carton,
     * read by {@link #contentsOf} and reduced by {@link BinContents#toOccupancy}.
     *
     * @param leaveOut a carton not to count, or null: the one being put away, when somebody already moved it into
     *                 the bin by hand. Counted, a carton that fills a bin exactly would not fit the bin it stands in
     */
    public Map<String, Occupancy> occupancyOf(Collection<String> locationCodes, String sku, String lotNumber,
                                              UUID leaveOut) {
        Set<SkuLot> group = Set.of(new SkuLot(sku, lotNumber));
        Map<String, Occupancy> out = new HashMap<>();
        contentsOf(locationCodes, group, leaveOut == null ? List.of() : List.of(leaveOut))
                .forEach((code, contents) -> out.put(code, contents.toOccupancy(group)));
        return out;
    }

    /** A SKU and a lot number of it; a null lot is a carton without one, which never matches a lot. */
    public record SkuLot(String sku, String lotNumber) {
    }

    /**
     * What a bin holds: its load over every carton on it, and of a group of cartons to put there, the SKUs and the
     * (SKU, lot) pairs it already holds - only those of the group, so the set stays as small as the group.
     */
    public record BinContents(int units, BigDecimal volumeM3, BigDecimal weightKg, Set<String> skus,
                              Set<SkuLot> lots) {

        public static final BinContents EMPTY =
                new BinContents(0, BigDecimal.ZERO, BigDecimal.ZERO, Set.of(), Set.of());

        /**
         * The bin as the ranker reads it for this group: it holds the SKU when it holds every SKU of the group, and the
         * lot when it holds every pair of the group and no pair lacks a lot. For a group of one this is the
         * single-carton rule exactly; for a bigger group a bin holding only part of it is not "the same" bin, or a
         * group would be grouped by whichever of its cartons the bin happens to share.
         */
        public Occupancy toOccupancy(Set<SkuLot> group) {
            boolean holdsSku = !group.isEmpty()
                    && group.stream().allMatch(pair -> pair.sku() != null && skus.contains(pair.sku()));
            boolean holdsLot = !group.isEmpty()
                    && group.stream().allMatch(pair -> pair.lotNumber() != null && lots.contains(pair));
            return new Occupancy(units, volumeM3, weightKg, holdsSku, holdsLot);
        }
    }

    /**
     * What each of these bins holds, in one statement; a bin holding nothing is absent. The load counts every carton
     * on the bin (see the class), the SKUs and pairs only those of {@code group}. A pair of the group without a lot
     * names its SKU but never a pair.
     *
     * @param leaveOut cartons not to count: the ones being put away or moved, wherever they stand now - counted, a
     *                 carton already in the bin would be judged against room it takes itself
     */
    public Map<String, BinContents> contentsOf(Collection<String> locationCodes, Set<SkuLot> group,
                                               Collection<UUID> leaveOut) {
        if (locationCodes.isEmpty()) {
            return Map.of();
        }
        List<SkuLot> pairs = List.copyOf(group);
        Map<String, BinContents> out = new HashMap<>();
        jdbc.query("""
                with g as (
                    select g.sku, g.lot_number, g.i
                    from unnest(cast(? as text[]), cast(? as text[])) with ordinality as g(sku, lot_number, i)),
                s as (
                    select s.location_code, s.stock_package_id, s.sku, s.lot_number, s.on_hand,
                           s.sku in (select g.sku from g) as in_group
                    from inventory.stock_item s
                    where s.location_code = any (?)
                      and s.on_hand > 0
                      and (s.stock_package_id is null or s.stock_package_id <> all (cast(? as uuid[])))),
                r as (
                    select s.location_code, s.stock_package_id, s.sku, bool_or(s.in_group) as in_group,
                           sum(s.on_hand) as on_hand
                    from s
                    group by s.location_code, s.stock_package_id, s.sku),
                held as (
                    select s.location_code, array_agg(distinct g.i) as pairs
                    from s
                    join g on g.sku = s.sku and g.lot_number = s.lot_number
                    group by s.location_code)
                select r.location_code,
                       sum(r.on_hand) as units,
                       sum(case when r.stock_package_id is null then 0
                                else coalesce(u.length_cm * u.width_cm * u.height_cm / 1000000, 0) end) as volume_m3,
                       coalesce(sum(u.weight_kg), 0) as weight_kg,
                       array_agg(distinct r.sku) filter (where r.in_group) as skus,
                       (select held.pairs from held where held.location_code = r.location_code) as pairs
                from r
                left join inventory.stock_package p on p.id = r.stock_package_id
                left join inventory.uom_conversions u on u.id = p.uom_conversion_id
                group by r.location_code""", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", pairs.stream().map(SkuLot::sku).toArray()));
            ps.setArray(2, ps.getConnection().createArrayOf("text",
                    pairs.stream().map(SkuLot::lotNumber).toArray()));
            ps.setArray(3, ps.getConnection().createArrayOf("text", locationCodes.toArray()));
            ps.setArray(4, ps.getConnection().createArrayOf("uuid", leaveOut.toArray()));
        }, rs -> {
            Set<String> skus = new HashSet<>();
            Array skuArray = rs.getArray("skus");
            if (skuArray != null) {
                skus.addAll(Arrays.asList((String[]) skuArray.getArray()));
            }
            Set<SkuLot> held = new HashSet<>();
            Array pairArray = rs.getArray("pairs");
            if (pairArray != null) {
                for (Long i : (Long[]) pairArray.getArray()) {
                    held.add(pairs.get(i.intValue() - 1));
                }
            }
            out.put(rs.getString("location_code"), new BinContents(rs.getInt("units"),
                    orZero(rs.getBigDecimal("volume_m3")), orZero(rs.getBigDecimal("weight_kg")), Set.copyOf(skus),
                    Set.copyOf(held)));
        });
        return out;
    }

    /**
     * What each of these cartons takes of a bin: its carton unit's size, whether its variant's cartons may be turned on
     * the horizontal ({@code rotatable}, docs 01; owner decision Q9) and its full weight - the unit's, null when nobody
     * recorded it. A carton that does not exist is absent.
     */
    public Map<UUID, Need> needsOf(Collection<UUID> cartonIds) {
        if (cartonIds.isEmpty()) {
            return Map.of();
        }
        record Row(UUID id, UUID variantId, BigDecimal length, BigDecimal width, BigDecimal height, BigDecimal weight) { }
        java.util.List<Row> rows = jdbc.query("""
                select p.id, u.variant_id, u.length_cm, u.width_cm, u.height_cm, u.weight_kg
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                where p.id = any (?)""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", cartonIds.toArray())),
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("variant_id", UUID.class),
                        rs.getBigDecimal("length_cm"), rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                        rs.getBigDecimal("weight_kg")));
        Map<UUID, Boolean> rotatable = rotations.of(rows.stream().map(Row::variantId).toList());
        Map<UUID, Need> out = new HashMap<>();
        for (Row row : rows) {
            out.put(row.id(), new Need(new CartonFit.Carton(row.length(), row.width(), row.height(),
                    VariantRotations.rotatable(rotatable, row.variantId())), row.weight()));
        }
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
                       coalesce(sum(u.weight_kg), 0) as weight_kg
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
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
                       coalesce(u.weight_kg, 0) as weight_kg
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
        record Row(String locationCode, UUID id, UUID unitId, String lpn, UUID variantId, BigDecimal length,
                   BigDecimal width, BigDecimal height) { }
        java.util.List<Row> rows = jdbc.query("""
                select p.location_code, p.id, p.uom_conversion_id, p.lpn, u.variant_id, u.length_cm, u.width_cm,
                       u.height_cm
                from inventory.stock_package p
                join inventory.uom_conversions u on u.id = p.uom_conversion_id
                where p.location_code = any (?)
                  and exists (select 1 from inventory.stock_item s where s.stock_package_id = p.id and s.on_hand > 0)
                order by p.lpn""",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", locationCodes.toArray())),
                (rs, n) -> new Row(rs.getString("location_code"), rs.getObject("id", UUID.class),
                        rs.getObject("uom_conversion_id", UUID.class), rs.getString("lpn"),
                        rs.getObject("variant_id", UUID.class), rs.getBigDecimal("length_cm"),
                        rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm")));
        Map<UUID, Boolean> rotatable = rotations.of(rows.stream().map(Row::variantId).toList());
        return rows.stream().map(row -> new CartonIn(row.locationCode(), row.id(), row.unitId(), row.lpn(),
                new CartonFit.Carton(row.length(), row.width(), row.height(),
                        VariantRotations.rotatable(rotatable, row.variantId())))).toList();
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
