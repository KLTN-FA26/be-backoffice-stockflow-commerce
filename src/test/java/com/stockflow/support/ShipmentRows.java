package com.stockflow.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * A {@code fulfillment.shipment} row for tests that hand an order's goods over through {@code OrderService} directly,
 * without packing: since G4-2a {@code ordering.order_shipment.id} is a foreign key to it (V20261021000500).
 */
public final class ShipmentRows {

    private ShipmentRows() {
    }

    /** A shipment of {@code orderId} waiting for dispatch at HCM, with that id; nothing when it exists. */
    public static UUID ready(JdbcTemplate jdbc, TransactionTemplate tx, UUID orderId, UUID shipmentId) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, delivery_mode, package_count,
                                                  cod_amount, ready_at, created_at, created_by)
                VALUES (?, ?, 'READY_TO_DISPATCH', ?, ?, 'PARCEL', 1, 0, NOW(), NOW(), 'test')
                ON CONFLICT DO NOTHING""", shipmentId, orderId, "T-" + shipmentId, DemoData.WAREHOUSE_HCM));
        return shipmentId;
    }
}
