package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.api.ShipmentCartons;
import com.stockflow.fulfillment.api.ShippedCarton;
import com.stockflow.fulfillment.api.ShippedCartons;
import com.stockflow.fulfillment.internal.repository.ShippedCartonQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ShipmentCartons}: the cartons a shipment, or an order's deliveries, carried - for a return receipt to check
 * what comes back against (team plan 4D-2; kltn-docs 03 BR-14, 09 BR-06).
 */
@Service
@Transactional(readOnly = true)
public class ShipmentCartonsService implements ShipmentCartons {

    private final ShippedCartonQueries queries;

    public ShipmentCartonsService(ShippedCartonQueries queries) {
        this.queries = queries;
    }

    @Override
    public Optional<ShippedCartons> ofShipment(UUID shipmentId) {
        return queries.shipment(shipmentId).map(s -> new ShippedCartons(s.id(), s.code(), s.orderId(), s.warehouseId(),
                s.status(), cartons(queries.cartonsOn(s.orderId(), List.of(s.id())))));
    }

    @Override
    public List<ShippedCarton> deliveredToOrder(UUID orderId) {
        return cartons(queries.cartonsOn(orderId, queries.deliveredShipmentsOf(orderId)));
    }

    private static List<ShippedCarton> cartons(List<ShippedCartonQueries.Row> rows) {
        return rows.stream().map(r -> new ShippedCarton(r.lpn(), r.sku(), r.lotNumber(), r.quantity())).toList();
    }
}
