package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A parcel confirmed off the vehicle without a scan because its label is damaged (SCRUM-474 UNLOAD-DESIGN point 5).
 *
 * @param packageId the parcel, from the load's {@code pendingUnloads} (its label cannot be read)
 * @param note      what is wrong with the label; optional
 */
public record UnloadOverrideRequest(@NotNull UUID packageId, @Size(max = 500) String note) {
}
