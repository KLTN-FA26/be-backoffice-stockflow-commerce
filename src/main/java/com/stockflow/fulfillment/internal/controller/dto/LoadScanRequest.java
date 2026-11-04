package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One parcel scanned onto the vehicle.
 *
 * @param scanId      made by the scanner, new for every scan: the same scan sent again changes nothing. Optional: the
 *                    server makes one when it is absent (a repeat without one is still harmless - the parcel is
 *                    already on the vehicle - but the client cannot tell its own retry from a new scan)
 * @param packageCode the code on the parcel's label ({@code PKG-<order>-<n>})
 */
public record LoadScanRequest(UUID scanId, @NotBlank @Size(max = 48) String packageCode) {
}
