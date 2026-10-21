package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.DeliveryFailureReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The carrier reported the order not delivered (kltn-docs 09 §4.3 step 2; G3-2).
 *
 * @param note required for OTHER
 */
@Schema(description = "Record a failed delivery")
public record DeliveryFailureRequest(

        @Schema(example = "CUSTOMER_ABSENT", description = "CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED or OTHER")
        @NotNull(message = "reasonCode is required")
        DeliveryFailureReason reasonCode,

        @Size(max = 500, message = "note is at most 500 characters")
        String note
) {
}
