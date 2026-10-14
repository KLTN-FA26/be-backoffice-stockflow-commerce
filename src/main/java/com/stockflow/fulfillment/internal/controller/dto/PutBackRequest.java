package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The bin the goods of a picked line go back onto: an active bin of the task's warehouse. */
public record PutBackRequest(@NotBlank @Size(max = 64) String locationCode) {
}
