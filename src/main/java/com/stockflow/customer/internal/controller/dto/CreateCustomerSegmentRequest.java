package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new customer group: {@code code} is fixed once created (upper case, digits, '_' or '-'). */
public record CreateCustomerSegmentRequest(
        @NotBlank(message = "code is required") @Size(max = 64, message = "code is at most 64 characters") String code,
        @NotBlank(message = "name is required") @Size(max = 200, message = "name is at most 200 characters") String name,
        @Size(max = 1000, message = "description is at most 1000 characters") String description) {
}
