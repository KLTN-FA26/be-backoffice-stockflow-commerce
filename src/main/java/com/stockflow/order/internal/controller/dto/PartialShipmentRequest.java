package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Sales allows, or stops, shipping an order in parts (PACK-PARTIAL); the note goes into the order's history. */
@Schema(description = "Allow or stop partial shipments of an order")
public record PartialShipmentRequest(

        @Schema(description = "true: packing may complete part of the order and part of it may be handed over; a mixed "
                + "order's stock may ship while its prints are made. false: it ships whole", example = "true")
        @NotNull(message = "allowed is required")
        Boolean allowed,

        @Schema(example = "Khách đồng ý nhận hàng sẵn trước, hàng in giao sau")
        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
