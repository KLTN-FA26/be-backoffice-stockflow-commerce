package com.stockflow.procurement.internal.domain;

import java.util.UUID;

/**
 * A storage location as receiving needs it: which warehouse it belongs to and, for an area, what
 * kind of area it is ({@code RECEIVING}, {@code QUALITY_CONTROL}, {@code QUARANTINE}...).
 *
 * @param areaType null for a bin
 */
public record ReceivingLocation(UUID id, String code, UUID warehouseId, String areaType) {

    public boolean isArea(String type, UUID warehouse) {
        return type.equals(areaType) && warehouseId.equals(warehouse);
    }
}
