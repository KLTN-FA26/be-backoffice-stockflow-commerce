package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.DeliveryMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The warehouse the order is produced and shipped from (SCRUM-423), and optionally how it travels (LP-1): a mode other
 * than the order's needs {@code sales-order-delivery-modes:UPDATE} as well.
 */
public record ReleaseOrderRequest(

        @NotNull UUID warehouseId,

        @Schema(example = "FULL_TRUCKLOAD", description = "Optional (LP-1): PARCEL or FULL_TRUCKLOAD; null keeps the "
                + "order's mode (PARCEL unless changed before). FULL_TRUCKLOAD with a balance collected on delivery is "
                + "409 DELIVERY_MODE_FTL_NO_COD and nothing is released")
        DeliveryMode deliveryMode
) {

    /** Without a delivery mode: the order keeps its own. */
    public ReleaseOrderRequest(UUID warehouseId) {
        this(warehouseId, null);
    }
}
