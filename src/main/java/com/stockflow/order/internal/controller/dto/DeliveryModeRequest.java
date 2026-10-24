package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.DeliveryMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The Order Coordinator changes how an order travels (LP-1); the note goes into the order's history. */
@Schema(description = "Change how an order travels")
public record DeliveryModeRequest(

        @Schema(example = "FULL_TRUCKLOAD", description = "PARCEL (parcels through a carrier) or FULL_TRUCKLOAD (a whole "
                + "truck or container, planned in 3D)")
        @NotNull(message = "deliveryMode is required")
        DeliveryMode deliveryMode,

        @Schema(example = "Khách lấy 2 container, giao nguyên chuyến")
        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
