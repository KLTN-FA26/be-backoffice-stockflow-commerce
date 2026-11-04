package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.HoldReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Put an order on hold (G3-2, kltn-docs 17 §5). CREDIT and DESIGN_MISMATCH are the system's: refused here.
 *
 * @param note required for OTHER
 */
@Schema(description = "Put an order on hold")
public record HoldOrderRequest(

        @Schema(example = "ADDRESS", description = "PAYMENT, ADDRESS, INVENTORY, CUSTOMER, FRAUD or OTHER")
        @NotNull(message = "reasonCode is required")
        HoldReason reasonCode,

        @Schema(example = "Sai phường, chờ khách xác nhận địa chỉ")
        @Size(max = 1000, message = "note is at most 1000 characters")
        String note
) {
}
