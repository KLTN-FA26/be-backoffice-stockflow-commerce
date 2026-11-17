package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The bin a picked carton goes back onto - an active bin of the task's warehouse - and the carton's LPN, scanned to
 * confirm it (kltn-docs 07 §4.5 step 14).
 */
public record PutBackRequest(@NotBlank @Size(max = 64) String locationCode,
                             @NotBlank @Size(max = 30) String lpn) {
}
