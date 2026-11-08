package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * An API_BOOKING carrier's pickup points, replaced whole (3-4; kltn-docs 09 BR-29, E24): one per warehouse.
 *
 * @param pickupPoints the full list; empty removes them all
 */
public record CarrierPickupPointsRequest(@NotNull @Size(max = 200) List<@Valid @NotNull Point> pickupPoints) {

    /**
     * @param provinceCode our province code (as an order's address carries it): picks the service code, bounds the
     *                     drop-offs
     * @param address      the warehouse as the carrier reads it: "[số nhà] [đường], [phường], [quận], [thành phố]"
     */
    public record Point(@NotNull UUID warehouseId, @NotNull @Size(max = 20) String provinceCode,
                        @NotNull @Size(max = 500) String address, @Size(max = 120) String contactName,
                        @Size(max = 32) String contactPhone) {
    }
}
