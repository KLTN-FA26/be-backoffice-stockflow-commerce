package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.CancellationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** A customer cancelling their own order (SCRUM-460). Both fields are optional. */
@Schema(description = "Cancel your own order, or ask for it to be cancelled once it is in production or the warehouse")
public record CancelOrderRequest(

        @Schema(example = "CUSTOMER_REQUEST", description = "Defaults to CUSTOMER_REQUEST")
        CancellationReasonCode reasonCode,

        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}
