package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Estimate how a source loads before it is packed (SCRUM-287 PR EST; advisory, LP2-1).
 *
 * @param sources        exactly one for now: {@code {type: ORDER, id}}; TRANSFER is reserved (400 until PR T)
 * @param vehicleTypeIds the vehicle types the plan may use; empty or absent: every active type
 * @param selectionMode  SINGLE_TYPE ("Một loại", the default) or FEWEST_VEHICLES ("Ít xe nhất")
 */
public record LoadEstimateRequest(@NotEmpty @Size(max = 1) List<@Valid @NotNull SourceRef> sources,
                                  @Size(max = 20) List<@NotNull UUID> vehicleTypeIds,
                                  @Pattern(regexp = "SINGLE_TYPE|FEWEST_VEHICLES") String selectionMode) {

    public record SourceRef(@NotNull @Pattern(regexp = "ORDER|TRANSFER") String type, @NotNull UUID id) { }
}
