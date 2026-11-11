package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * What was found on one line: whether its carton was scanned at the bin (kltn-docs 20 §4.2 b4). Never a number of
 * units: a carton is not opened to count it, its count is its standard count (BR-14), so the scan is the count.
 */
@Schema(description = "Count one line: its carton scanned present (true) or not found (false)")
public record CountLineRequest(

        @Schema(description = "The carton was scanned at the bin (true) or not found there (false)")
        @NotNull(message = "found is required")
        Boolean found
) {
}
