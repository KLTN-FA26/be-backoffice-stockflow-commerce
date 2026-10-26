package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.PutawayRanker.Occupancy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * What the given bins hold right now, for ranking a putaway (SCRUM-92). One grouped read of the inventory schema:
 * the weight comes from the item master of the same schema, never from another module's tables.
 *
 * <p>A bin's units are everything on its rows - any SKU, any status, loose or inside a carton - because the
 * shelf is as full of a quarantined or held unit as of a sellable one (BR-SLT-001, BR-SLT-003). A SKU without a
 * recorded weight weighs nothing here; the rule then cannot be broken by a number nobody knows.</p>
 */
@Repository
public class BinOccupancyQuery {

    private final JdbcTemplate jdbc;

    public BinOccupancyQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The occupancy of each of these bins; a bin holding nothing is absent, which the ranker reads as empty.
     * {@code holdsSku} / {@code holdsLot} refer to the SKU (and lot number) being put away.
     */
    public Map<String, Occupancy> occupancyOf(Collection<String> locationCodes, String sku, String lotNumber) {
        if (locationCodes.isEmpty()) {
            return Map.of();
        }
        Map<String, Occupancy> out = new HashMap<>();
        jdbc.query("""
                select s.location_code,
                       sum(s.on_hand) as units,
                       sum(s.on_hand * coalesce(i.weight_kg, 0)) as weight_kg,
                       coalesce(bool_or(s.on_hand > 0 and s.sku = ?), false) as holds_sku,
                       coalesce(bool_or(s.on_hand > 0 and s.sku = ? and s.lot_number is not distinct from ?
                                        and ? is not null), false) as holds_lot
                from inventory.stock_item s
                left join inventory.inventory_items i on i.sku = s.sku
                where s.location_code = any (?)
                group by s.location_code
                having sum(s.on_hand) > 0""", ps -> {
            ps.setString(1, sku);
            ps.setString(2, sku);
            ps.setString(3, lotNumber);
            ps.setString(4, lotNumber);
            ps.setArray(5, ps.getConnection().createArrayOf("text", locationCodes.toArray()));
        }, rs -> {
            BigDecimal weight = rs.getBigDecimal("weight_kg");
            out.put(rs.getString("location_code"), new Occupancy(rs.getInt("units"),
                    weight == null ? BigDecimal.ZERO : weight, rs.getBoolean("holds_sku"),
                    rs.getBoolean("holds_lot")));
        });
        return out;
    }
}
