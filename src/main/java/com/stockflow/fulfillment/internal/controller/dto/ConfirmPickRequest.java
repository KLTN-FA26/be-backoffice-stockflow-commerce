package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One scan of a pick line, in the order the picker makes it (docs 07 step 6): the location, then the SKU or
 * barcode, then the quantity.
 */
public record ConfirmPickRequest(@NotBlank @Size(max = 64) String locationCode,
                                 @NotBlank @Size(max = 64) String scannedCode,
                                 @NotNull @Positive Integer quantity) {
}
