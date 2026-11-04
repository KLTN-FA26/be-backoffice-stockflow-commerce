package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.order.api.OrderService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a screen needs beside a shipment (FE #61): the fulfillment task it was packed in - where its label is
 * reprinted after {@code LABEL_OUTDATED} - and the currency of its order, shown next to every COD amount.
 */
@Component
class ShipmentContext {

    /** The currency when an order cannot be read: the business sells in VND only (kltn-docs 15). */
    static final String DEFAULT_CURRENCY = "VND";

    private final PackJpaRepository packs;
    private final OrderService orders;

    ShipmentContext(PackJpaRepository packs, OrderService orders) {
        this.packs = packs;
        this.orders = orders;
    }

    /** Shipment id → task id (the pick its pack belongs to); absent for a shipment made without a pack. */
    Map<UUID, UUID> tasks(Collection<ShipmentJpaEntity> shipments) {
        var packIds = shipments.stream().map(ShipmentJpaEntity::getPackId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> pickOfPack = packIds.isEmpty() ? Map.of() : packs.findAllById(packIds).stream()
                .collect(Collectors.toMap(PackJpaEntity::getId, PackJpaEntity::getPickId));
        Map<UUID, UUID> tasks = new HashMap<>();
        shipments.forEach(s -> {
            if (s.getPackId() != null && pickOfPack.containsKey(s.getPackId())) {
                tasks.put(s.getId(), pickOfPack.get(s.getPackId()));
            }
        });
        return tasks;
    }

    /** Order id → its currency, one read per distinct order. */
    Map<UUID, String> currencies(Collection<UUID> orderIds) {
        Map<UUID, String> currencies = new HashMap<>();
        orderIds.stream().distinct().forEach(id -> currencies.put(id, orders.findById(id)
                .map(o -> o.total().currency().getCurrencyCode()).orElse(DEFAULT_CURRENCY)));
        return currencies;
    }
}
