package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * A new empty package.
 *
 * @param packageType       CARTON (default), BAG, PALLET, CRATE or OTHER
 * @param kitOverrideReason KIT-FORCE (lead's decision 2026-10-22): why an item that ships as several boxes
 *                          ({@code kitSuggested}) will go in this ordinary package rather than in a kit; may also come
 *                          with the scan or the seal
 */
public record OpenPackageRequest(@Size(max = 16) String packageType, @Size(max = 500) String kitOverrideReason) {
}
