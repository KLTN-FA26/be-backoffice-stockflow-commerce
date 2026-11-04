package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code reason} is required to reopen a closed customer; kept in the customer's status history. */
public record ChangeCustomerStatusRequest(@NotBlank String status, @Size(max = 1000) String reason) {
}
