package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * LP2-8: an unopened stock carton shipped as it is - its label (LPN) scanned at the transfer's packing table.
 *
 * @param packageType CARTON (default), BAG, PALLET, CRATE or OTHER
 */
public record TransferCartonRequest(@NotBlank @Size(max = 30) String lpn, @Size(max = 16) String packageType) {
}
