package com.stockflow.order.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The warehouse the order is produced and shipped from (SCRUM-423). */
public record ReleaseOrderRequest(@NotNull UUID warehouseId) {
}
