package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The QUALITY_CONTROL area the line's goods were moved to. */
public record MoveToQcRequest(@NotBlank @Size(max = 64) String qcLocationCode) {
}
