package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a load is called off before it left. */
public record CancelLoadRequest(@NotBlank @Size(max = 500) String reason) {
}
