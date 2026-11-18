package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Sales change an order's delivery address to one of the customer's shipping addresses (WF04 G2; kltn-docs 17 BR-16). */
@Schema(description = "Change the delivery address of an order to one of its customer's shipping addresses")
public record ShippingAddressChangeRequest(

        @Schema(description = "A SHIPPING address of the order's customer (GET /customers/{id}/addresses)")
        @NotNull(message = "shippingAddressId is required")
        UUID shippingAddressId
) {
}
