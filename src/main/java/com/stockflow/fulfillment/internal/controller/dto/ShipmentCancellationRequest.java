package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * The warehouse manager cancels a shipment waiting for dispatch (kltn-docs 09 §5.1 "Cancelled"; owner decision
 * RTM-RTDCANCEL, 2026-11-19).
 *
 * @param reason            why it is cancelled; required, kept on the shipment's timeline and on each carton
 * @param damagedPackageIds the shipment's cartons found damaged ({@code packages[].packageId} of the shipment): they go
 *                          into quarantine, the others back onto a bin; empty or absent for none
 */
public record ShipmentCancellationRequest(
        @NotBlank @Size(max = 500) String reason,
        @Size(max = 500) List<@NotNull UUID> damagedPackageIds) {
}
