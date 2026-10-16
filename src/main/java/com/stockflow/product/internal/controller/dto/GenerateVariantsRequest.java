package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Restricts a generation; an absent body or {@code {}} generates the whole matrix. */
public record GenerateVariantsRequest(
        @Schema(description = "Axis attribute id to the option value ids to use on that axis. An axis left out "
                + "uses all its options.")
        Map<UUID, List<@NotNull UUID>> optionValueIds
) {
}
