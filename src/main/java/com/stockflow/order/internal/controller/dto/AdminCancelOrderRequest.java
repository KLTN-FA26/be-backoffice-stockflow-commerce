package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** SCRUM-242/WBS 3.17.4: unlike the customer-facing cancellation, an admin/sales-initiated one
 *  must always record a reason — there is no sensible default like "CUSTOMER_REQUEST" here. */
@Schema(description = "Cancel any order as an admin or sales rep")
public record AdminCancelOrderRequest(

        @NotBlank(message = "reason is required")
        String reason
) {
}
