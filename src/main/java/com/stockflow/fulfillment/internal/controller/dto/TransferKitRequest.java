package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * "Đóng bộ" at a transfer's packing (SCRUM-287 PR T, LP2-6): the parts of a kit for one unit of a transfer line whose
 * item ships as several boxes. {@code partCount} as for an order ({@link OpenKitRequest}).
 */
public record TransferKitRequest(@NotNull UUID transferLineId,
                                 @Min(2) @Max(99) Integer partCount,
                                 @Size(max = 16) String packageType) {
}
