package com.stockflow.production.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The workshop (warehouse) that makes the sample. */
public record SubmitSampleRequest(@NotNull(message = "warehouseId is required") UUID warehouseId) {
}
