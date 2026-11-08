package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a screen needs beside a shipment (FE #61): the fulfillment task it was packed in - where its label is
 * reprinted after {@code LABEL_OUTDATED}. (It also gave the order's currency for the COD amounts, which went with COD.)
 */
@Component
class ShipmentContext {

    private final PackJpaRepository packs;

    ShipmentContext(PackJpaRepository packs) {
        this.packs = packs;
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
}
