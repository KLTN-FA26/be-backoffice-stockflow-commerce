package com.stockflow.production.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import java.util.List;

/** A run's output: units printed, and which of them were spoilt and why (good + scrap = printed). */
public record OutputRequest(
        @Min(value = 1, message = "printed is at least 1") int printed,
        List<@Valid ScrapLineRequest> scrap
) {
}
