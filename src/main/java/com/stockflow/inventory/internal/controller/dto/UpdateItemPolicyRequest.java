package com.stockflow.inventory.internal.controller.dto;

import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.TrackingMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PUT replaces the item's stock policy; {@code version} is the item's. There are no thresholds and no expiry settings
 * to send: the module raises no stock alert and tracks no expiry (kltn-docs 20 BR-13, 01 BR-17).
 */
public record UpdateItemPolicyRequest(@NotNull @Min(0) Long version,
        @NotNull RemovalStrategy removalStrategy, @NotNull TrackingMode trackingMode) {}
