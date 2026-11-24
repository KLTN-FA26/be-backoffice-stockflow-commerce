package com.stockflow.order.internal.repository;

import com.stockflow.order.api.OrderShipmentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What of an order reached the customer and what of it came back on its returns (BE-35, contract F-2): the facts the
 * customer's order views need to tell an order that came back whole from one the customer kept part of. One read of
 * this module's own tables for any number of orders, so a page of list rows costs one statement, not one per row.
 *
 * <p>Two different "came back" are kept apart on purpose. A shipment the carrier brought back (status RETURNED) never
 * counts as delivered, so its units are in neither figure; only the returns (RMA) the customer opened against delivered
 * goods count as returned, and only by what was actually received back ({@code received_quantity}, every parcel so far
 * together; null - nothing received yet, or a refund without the goods coming back - counts 0). A return that was
 * rejected or cancelled received nothing, so no status filter is needed.</p>
 */
@Repository
public class DeliveredAndReturnedUnits {

    /**
     * @param deliveredShipments   the order's shipments recorded DELIVERED
     * @param deliveredUnits       the units those shipments carried
     * @param returnedUnits        the units received back on the order's returns
     * @param undeliveredShipments the shipments that ended undelivered: brought back by the carrier (RETURNED), or
     *                             closed as lost ({@code units_closed_at}, WF-05 Q-12)
     * @param openShipments        the shipments still to be decided: with the carrier, or failed and neither brought
     *                             back nor closed as lost
     * @param outstandingUnits     the units in no shipment that still holds its units and not closed on their line:
     *                             what is still to ship ({@code Order.outstandingUnits})
     */
    public record Units(int deliveredShipments, long deliveredUnits, long returnedUnits, int undeliveredShipments,
                        int openShipments, long outstandingUnits) {

        /** An order nothing is known of: nothing delivered, nothing returned. */
        public static final Units NONE = new Units(0, 0, 0, 0, 0, 0);

        /** As read before the carrier's part was asked for: delivered and returned units only. */
        public Units(int deliveredShipments, long deliveredUnits, long returnedUnits) {
            this(deliveredShipments, deliveredUnits, returnedUnits, 0, 0, 0);
        }
    }

    private final JdbcTemplate jdbc;

    public DeliveredAndReturnedUnits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The units of each of {@code orderIds}, in one statement. An unknown order is absent; empty input gives an empty
     * map without touching the database.
     */
    public Map<UUID, Units> of(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Units> units = new HashMap<>();
        jdbc.query("""
                select o.id,
                       (select count(*)
                          from ordering.order_shipment s
                         where s.order_id = o.id and s.status = '%1$s') as delivered_shipments,
                       (select coalesce(sum(sl.quantity), 0)
                          from ordering.order_shipment s
                          join ordering.order_shipment_line sl on sl.shipment_id = s.id
                         where s.order_id = o.id and s.status = '%1$s') as delivered_units,
                       (select coalesce(sum(rl.received_quantity), 0)
                          from ordering.return_request r
                          join ordering.return_request_line rl on rl.return_request_id = r.id
                         where r.order_id = o.id) as returned_units,
                       (select count(*)
                          from ordering.order_shipment s
                         where s.order_id = o.id
                           and (s.status = 'RETURNED' or s.units_closed_at is not null)) as undelivered_shipments,
                       (select count(*)
                          from ordering.order_shipment s
                         where s.order_id = o.id
                           and (s.status = 'HANDED_OVER'
                                or s.status = 'DELIVERY_FAILED' and s.units_closed_at is null)) as open_shipments,
                       (select coalesce(sum(l.quantity - l.cancelled_quantity), 0)
                          from ordering.order_line l
                         where l.order_id = o.id)
                       - (select coalesce(sum(sl.quantity), 0)
                            from ordering.order_shipment s
                            join ordering.order_shipment_line sl on sl.shipment_id = s.id
                           where s.order_id = o.id and s.units_closed_at is null) as outstanding_units
                  from ordering.customer_order o
                 where o.id = any (?)""".formatted(OrderShipmentStatus.DELIVERED.name()),
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", orderIds.toArray())),
                rs -> {
                    units.put(rs.getObject("id", UUID.class), new Units(rs.getInt("delivered_shipments"),
                            rs.getLong("delivered_units"), rs.getLong("returned_units"),
                            rs.getInt("undelivered_shipments"), rs.getInt("open_shipments"),
                            rs.getLong("outstanding_units")));
                });
        return units;
    }
}
