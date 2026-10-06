package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Checkout request body.
 *
 * <p>{@code @Valid} on the list element is what makes the nested constraints run — without it,
 * Bean Validation checks the list is non-empty and stops there, and a line with quantity zero
 * sails through to the domain. A common and quiet mistake.</p>
 */
@Schema(description = "Turn a cart into an order")
public record PlaceOrderRequest(

        @Schema(description = "Idempotency key; resending it returns the original order")
        @NotNull(message = "requestId is required")
        UUID requestId,

        @NotNull(message = "customerId is required")
        UUID customerId,

        @Schema(description = "Saved SHIPPING address; omit to use the customer's default")
        UUID shippingAddressId,

        @Schema(description = "Saved BILLING address; omit to use its default, then shipping")
        UUID billingAddressId,

        @NotEmpty(message = "An order needs at least one line")
        List<@Valid Line> lines
) {

    @Schema(description = "One order line")
    public record Line(

            @Schema(example = "SOFA-3S-GREY")
            @NotBlank(message = "sku is required")
            String sku,

            @Min(value = 1, message = "quantity must be at least 1")
            int quantity,

            @Schema(description = "Agreed unit price, in the order currency")
            @NotNull(message = "unitPrice is required")
            @DecimalMin(value = "0.0", message = "unitPrice must not be negative")
            BigDecimal unitPrice,

            @Schema(description = "Frozen design for print-on-demand items; null otherwise")
            UUID designSnapshotId
    ) {
    }
}
