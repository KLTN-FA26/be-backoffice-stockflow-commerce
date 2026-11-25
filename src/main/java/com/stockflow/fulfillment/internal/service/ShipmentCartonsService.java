package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.api.ShipmentCartons;
import com.stockflow.fulfillment.api.ShippedCarton;
import com.stockflow.fulfillment.api.ShippedCartons;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShippedCartonQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ShipmentCartons}: the cartons a shipment, or an order's deliveries, carried - for a return receipt to check
 * what comes back against (team plan 4D-2; kltn-docs 03 BR-14, 09 BR-06).
 */
@Service
@Transactional(readOnly = true)
public class ShipmentCartonsService implements ShipmentCartons {

    private final ShippedCartonQueries queries;
    private final ShipmentJpaRepository shipments;

    public ShipmentCartonsService(ShippedCartonQueries queries, ShipmentJpaRepository shipments) {
        this.queries = queries;
        this.shipments = shipments;
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

    @Override
    public Map<UUID, String> codesOf(Collection<UUID> shipmentIds) {
        List<UUID> ids = shipmentIds == null ? List.of()
                : shipmentIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : shipments.findByIdIn(ids).stream()
                .collect(Collectors.toMap(ShipmentJpaEntity::getId, ShipmentJpaEntity::getCode));
    }

    private static List<ShippedCarton> cartons(List<ShippedCartonQueries.Row> rows) {
        return rows.stream().map(r -> new ShippedCarton(r.lpn(), r.sku(), r.lotNumber(), r.quantity())).toList();
    }
}
