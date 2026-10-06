package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeCustomerStatusRequest(@NotBlank String status) {
}
