package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A step on a load plan decided on the version read; {@code note} where the step takes one. */
public record LoadPlanVersionRequest(@NotNull Long expectedVersion, @Size(max = 500) String note) {
}
