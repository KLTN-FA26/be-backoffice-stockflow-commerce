package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a booking fell through before loading started. */
public record CancelLoadRequest(@NotBlank @Size(max = 500) String reason) {
}
