package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One scan of a pick line, in the order the picker makes it (kltn-docs 07 §4.2 step 5): the bin, then the carton's LPN.
 * Only the line's own LPN confirms it - not the SKU, not a barcode, not another carton - and there is no quantity: the
 * carton is taken whole (BR-04, BR-10).
 */
public record ConfirmPickRequest(@NotBlank @Size(max = 64) String locationCode,
                                 @NotBlank @Size(max = 30) String lpn) {
}
