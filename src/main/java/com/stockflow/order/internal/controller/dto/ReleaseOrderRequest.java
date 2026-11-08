package com.stockflow.order.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * The warehouse the order is shipped from (SCRUM-423). How it travels is not chosen: every order ships as a full
 * truckload (kltn-docs 09 BR-08).
 */
public record ReleaseOrderRequest(@NotNull UUID warehouseId) {
}
