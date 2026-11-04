package com.stockflow.production.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Stop a job on the press (decision Q2): the run's output so far, and why. */
public record StopProductionRequest(
        @Min(value = 0, message = "printed cannot be negative") int printed,
        List<@Valid ScrapLineRequest> scrap,
        @NotBlank(message = "reason is required") @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}
