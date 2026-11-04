package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Rename a customer group; {@code version} as read. */
public record UpdateCustomerSegmentRequest(
        @NotBlank(message = "name is required") @Size(max = 200, message = "name is at most 200 characters") String name,
        @Size(max = 1000, message = "description is at most 1000 characters") String description,
        @NotNull(message = "version is required") @PositiveOrZero Long version) {
}
