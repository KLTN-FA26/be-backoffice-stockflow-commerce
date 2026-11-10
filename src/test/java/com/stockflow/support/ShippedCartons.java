package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Cartons that left a warehouse on a shipment, as the check station and the dispatch leave them (team plan 4D-2): the
 * carton CONSUMED (its row at nought), the pick line that took it PICKED, the shipping carton row LABELED on a shipment
 * of the given status. Written as rows, with triggers and foreign keys off for the one transaction (what the goods went
 * through before is {@code CartonCheckStationIntegrationTest}'s subject, not a return receipt's): the facts a return
 * receipt reads are the same either way.
 */
public final class ShippedCartons {

    /** One shipped carton. */
    public record Carton(UUID id, String lpn, int quantity) {
    }

    /** One shipment and the cartons it carried. */
    public record Shipped(UUID orderId, UUID shipmentId, String shipmentCode, List<Carton> cartons) {

        public List<String> lpns() {
            return cartons.stream().map(Carton::lpn).toList();
        }
    }

    private ShippedCartons() {
    }

    /**
     * {@code count} cartons of {@code perCarton} units of {@code sku}, in {@code lot}, picked for {@code orderLineId} of
     * {@code orderId} at HCM (a SKU that has a carton unit already keeps its factor; {@code perCarton} is for one that has
     * none yet) and carried by one new shipment in {@code shipmentStatus} ({@code DELIVERED} or
     * {@code RETURNED}, as the carrier left it). {@code staff} is the user the check station's rows name.
     */
    public static Shipped ship(JdbcTemplate jdbc, TransactionTemplate tx, UUID orderId, UUID orderLineId, String sku,
                               int count, int perCarton, String lot, String shipmentStatus, UUID staff) {
        UUID newPick = Identifiers.newId();
        UUID newPack = Identifiers.newId();
        UUID[] taken = new UUID[2];
        UUID shipment = Identifiers.newId();
        String shipmentCode = "S" + shipment.toString().replace("-", "").substring(0, 12).toUpperCase();
        List<Carton> shipped = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            String number = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?",
                    String.class, orderId);
            // One task and one session per order (uk_fulfillment_pick_order): a second shipment of the order shares them.
            List<UUID> existing = jdbc.queryForList("SELECT id FROM fulfillment.pick WHERE order_id = ?", UUID.class,
                    orderId);
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
                        VALUES (?, ?, ?, ?, 'PICKED', NOW())""", newPick, orderId, number, DemoData.WAREHOUSE_HCM);
                jdbc.update("""
                        INSERT INTO fulfillment.pack (id, pick_id, status, started_at, started_by, packed_at, packed_by,
                                                      created_at)
                        VALUES (?, ?, 'COMPLETED', NOW(), ?, NOW(), ?, NOW())""", newPack, newPick, staff, staff);
                taken[0] = newPick;
                taken[1] = newPack;
            } else {
                taken[0] = existing.getFirst();
                taken[1] = jdbc.queryForObject("SELECT id FROM fulfillment.pack WHERE pick_id = ?", UUID.class,
                        taken[0]);
            }
            UUID pick = taken[0];
            UUID pack = taken[1];
            jdbc.update("""
                    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, created_at,
                                                      handed_over_at, outcome_at, whole_order_handover)
                    VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), NOW(), NOW(), TRUE)""", shipment, pack, orderId,
                    shipmentStatus, shipmentCode, DemoData.WAREHOUSE_HCM);
            var stocked = CartonRows.stock(jdbc, sku, "HCM-A01-1-A", count, perCarton, lot,
                    Instant.now().minusSeconds(30L * 86_400), "AVAILABLE");
            int no = Math.max(jdbc.queryForObject("SELECT COALESCE(MAX(package_no), 0) FROM fulfillment.package WHERE "
                    + "pack_id = ?", Integer.class, pack), jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no), 0) FROM "
                    + "fulfillment.pick_line WHERE pick_id = ?", Integer.class, pick));
            for (CartonRows.Carton carton : stocked) {
                no++;
                // The SKU's carton unit says what a carton holds (a demo sofa: one piece), whatever the caller hoped.
                int held = jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE id = ?", Integer.class,
                        carton.stockItemId());
                jdbc.update("UPDATE inventory.stock_package SET status = 'CONSUMED', unopened = FALSE, closed_at = NOW() "
                        + "WHERE id = ?", carton.id());
                jdbc.update("UPDATE inventory.stock_item SET on_hand = 0 WHERE id = ?", carton.stockItemId());
                UUID line = Identifiers.newId();
                jdbc.update("""
                        INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, lot_number,
                            sequence_no, allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
                        VALUES (?, ?, ?, ?, 'HCM-A01-1-A', ?, ?, ?, ?, 'PICKED', ?, TRUE, ?, NOW(), NOW())""", line, pick,
                        orderLineId, sku, lot, no, held, held, carton.lpn(), staff);
                jdbc.update("""
                        INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, label_code, status,
                            pick_line_id, lpn, checked_at, checked_by, labeled_at, labeled_by, label_version, weight_kg,
                            length_cm, width_cm, height_cm, dim_weight_kg, sealed_at)
                        VALUES (?, ?, ?, ?, ?, 'LABELED', ?, ?, NOW(), ?, NOW(), ?, 1, 12, 60, 40, 40, 16, NULL)""",
                        Identifiers.newId(), pack, shipment, no, shipmentCode + "-C" + no, line, carton.lpn(), staff,
                        staff);
                shipped.add(new Carton(carton.id(), carton.lpn(), held));
            }
        });
        return new Shipped(orderId, shipment, shipmentCode, shipped);
    }

    /**
     * The same carton shipped again, for another order, after it came back (review L4 of cluster 1): a new pick (so a
     * later {@code consumed_at} on the LPN), pack, DELIVERED shipment and labelled carton row for {@code lpn}, which is
     * CONSUMED again. Returns the new shipment.
     */
    public static UUID shipAgain(JdbcTemplate jdbc, TransactionTemplate tx, String lpn, UUID orderId, UUID orderLineId,
                                 String sku, UUID staff) {
        UUID pick = Identifiers.newId();
        UUID pack = Identifiers.newId();
        UUID shipment = Identifiers.newId();
        UUID line = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            String number = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?",
                    String.class, orderId);
            int held = jdbc.queryForObject("SELECT received_qty FROM inventory.stock_package WHERE lpn = ?",
                    Integer.class, lpn);
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
                    VALUES (?, ?, ?, ?, 'PICKED', NOW())""", pick, orderId, number, DemoData.WAREHOUSE_HCM);
            jdbc.update("""
                    INSERT INTO fulfillment.pack (id, pick_id, status, started_at, started_by, packed_at, packed_by,
                                                  created_at)
                    VALUES (?, ?, 'COMPLETED', NOW(), ?, NOW(), ?, NOW())""", pack, pick, staff, staff);
            jdbc.update("""
                    INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at,
                                                      created_at, handed_over_at, outcome_at, whole_order_handover)
                    VALUES (?, ?, ?, 'DELIVERED', ?, ?, NOW(), NOW(), NOW(), NOW(), TRUE)""", shipment, pack, orderId,
                    "S" + shipment.toString().replace("-", "").substring(0, 12).toUpperCase(), DemoData.WAREHOUSE_HCM);
            jdbc.update("""
                    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, sequence_no,
                        allocated_qty, picked_qty, status, lpn, whole_carton, picked_by, picked_at, consumed_at)
                    VALUES (?, ?, ?, ?, 'HCM-A01-1-A', 1, ?, ?, 'PICKED', ?, TRUE, ?, NOW(), clock_timestamp())""", line,
                    pick, orderLineId, sku, held, held, lpn, staff);
            jdbc.update("""
                    INSERT INTO fulfillment.package (id, pack_id, shipment_id, package_no, label_code, status, pick_line_id,
                        lpn, checked_at, checked_by, labeled_at, labeled_by, label_version, weight_kg, length_cm, width_cm,
                        height_cm, dim_weight_kg)
                    VALUES (?, ?, ?, 1, ?, 'LABELED', ?, ?, NOW(), ?, NOW(), ?, 1, 12, 60, 40, 40, 16)""",
                    Identifiers.newId(), pack, shipment, "AGAIN-" + line.toString().substring(0, 8), line, lpn, staff,
                    staff);
            jdbc.update("UPDATE inventory.stock_package SET status = 'CONSUMED', unopened = FALSE, closed_at = NOW() "
                    + "WHERE lpn = ?", lpn);
            jdbc.update("UPDATE inventory.stock_item SET on_hand = 0 WHERE stock_package_id = (SELECT id FROM "
                    + "inventory.stock_package WHERE lpn = ?)", lpn);
        });
        return shipment;
    }
}
