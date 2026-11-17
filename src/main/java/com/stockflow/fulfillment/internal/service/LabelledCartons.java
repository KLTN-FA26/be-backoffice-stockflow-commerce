package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CartonCheckStore;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.order.spi.LabelledNotHandedOver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The order's question of {@code order :: spi} (owner decision WF4-CLOSE-REST, 2026-11-13; kltn-docs 17 §4.8 step 33):
 * the units of an order in cartons checked and labelled at the check station and not handed over yet - in a shipment
 * waiting at dispatch for its load (READY_TO_DISPATCH, a vehicle of its plan may have left with part of it), or labelled
 * and not in a shipment yet. Those stay on the order when Sales closes its rest, and are delivered. A cancelled task, a
 * cancelled shipment and a removed carton count for nothing.
 *
 * <p>In the caller's transaction: the closure asks under the order's lock, and the check station asks again after a
 * label, under the same lock ({@link CartonCheckService#label}).</p>
 */
@Component
class LabelledCartons implements LabelledNotHandedOver {

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final ShipmentJpaRepository shipments;
    private final CartonCheckStore store;

    LabelledCartons(PickJpaRepository picks, PackJpaRepository packs, ShipmentJpaRepository shipments,
                    CartonCheckStore store) {
        this.picks = picks;
        this.packs = packs;
        this.shipments = shipments;
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public Map<UUID, Integer> unitsOf(UUID orderId) {
        Map<UUID, Integer> units = new LinkedHashMap<>();
        for (PickJpaEntity pick : picks.findAllByOrderId(orderId)) {
            if (pick.getStatus() == PickStatus.CANCELLED) {
                continue;
            }
            packs.findByPickId(pick.getId()).ifPresent(pack -> {
                Set<UUID> waiting = shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId()).stream()
                        .filter(row -> row.getStatus() == ShipmentStatus.READY_TO_DISPATCH)
                        .map(ShipmentJpaEntity::getId).collect(Collectors.toSet());
                CartonCheck check = store.load(pack, orderId, pick.getOrderNumber());
                check.cartons().stream()
                        .filter(c -> c.status() == PackageStatus.LABELED
                                && (c.shipmentId() == null || waiting.contains(c.shipmentId())))
                        .forEach(c -> units.merge(c.orderLineId(), c.quantity(), Integer::sum));
            });
        }
        return units;
    }
}
