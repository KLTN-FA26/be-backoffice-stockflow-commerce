package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A parcel of a cancelled order taken off the vehicle (SCRUM-392).
 *
 * @param packageCode the code on the parcel's label ({@code PKG-<order>-<n>})
 */
public record UnloadRequest(@NotBlank @Size(max = 48) String packageCode) {
}
