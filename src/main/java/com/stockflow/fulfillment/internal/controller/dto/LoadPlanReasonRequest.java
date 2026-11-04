package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Reopening or cancelling a load plan: the version read and why (09 §5; LP-11: the reason goes to its bookings). */
public record LoadPlanReasonRequest(@NotNull Long expectedVersion, @NotBlank @Size(max = 500) String reason) {
}
