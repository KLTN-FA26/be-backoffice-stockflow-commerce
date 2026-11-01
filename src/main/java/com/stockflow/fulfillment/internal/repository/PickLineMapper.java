package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.domain.PickLine;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;

import java.util.UUID;

/** Copies pick lines between the table mapping and the domain model, in both directions. */
public final class PickLineMapper {

    private PickLineMapper() {
    }

    public static PickLine toDomain(PickLineJpaEntity e) {
        return new PickLine(e.getId(), e.getOrderLineId(), e.getReservationId(), e.getSku(), e.getLocationCode(),
                e.getLotNumber(), e.getSequenceNo(), e.getAllocatedQty(), e.getPickedQty(), e.getStatus(),
                e.getPickedBy(), e.getPickedAt(), e.getConsumedAt(), e.getScannedLocationCode(), e.getScannedCode(),
                e.getPutBackQty(), e.getPutBackLocationCode(), e.getPutBackBy(), e.getPutBackAt(), e.getShortQty(),
                e.getLpn(), e.isWholeCarton());
    }

    /** A row for a line the aggregate just planned. */
    public static PickLineJpaEntity toNewEntity(UUID pickId, PickLine line) {
        var entity = new PickLineJpaEntity(line.id(), pickId, line.orderLineId(), line.reservationId(), line.sku(),
                line.locationCode(), line.lotNumber(), line.sequenceNo(), line.allocatedQty(), line.lpn(),
                line.wholeCarton());
        applyTo(line, entity);
        return entity;
    }

    public static void applyTo(PickLine line, PickLineJpaEntity entity) {
        entity.apply(line.pickedQty(), line.status(), line.pickedBy(), line.pickedAt(), line.consumedAt(),
                line.scannedLocationCode(), line.scannedCode(), line.putBackQty(), line.putBackLocationCode(),
                line.putBackBy(), line.putBackAt());
        entity.recordShort(line.shortQty());
    }
}
