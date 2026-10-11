package com.stockflow.product.internal.controller.dto;

import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

/** PUT replaces configuration; null thresholds explicitly clear them. */
public record UpdateInventoryControlRequest(@NotNull @Min(0) Long version,
        @Min(0) Integer reorderPoint, @Min(0) Integer safetyStock,
        @NotNull RemovalStrategy removalStrategy, @NotNull TrackingMode trackingMode,
        @NotNull Boolean expiryTracked, @Min(1) @Max(36500) Integer maxShelfLifeDays) {}
