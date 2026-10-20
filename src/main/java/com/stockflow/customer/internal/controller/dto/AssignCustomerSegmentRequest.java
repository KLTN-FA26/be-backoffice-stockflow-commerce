package com.stockflow.customer.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/** The group a customer belongs to; {@code segmentId} null takes the customer out of every group. */
public record AssignCustomerSegmentRequest(
        UUID segmentId,
        @NotNull(message = "version is required") @PositiveOrZero Long version) {
}
