package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What was found on one line: whether its carton was scanned at the bin (kltn-docs 20 §4.2 b4). Never a number of
 * units: a carton is not opened to count it, its count is its standard count (BR-14), so the scan is the count - and
 * a scan carries the LPN, which the service checks is the line's own carton's.
 */
@Schema(description = "Count one line: its carton scanned present (true, with the LPN scanned) or not found (false)")
public record CountLineRequest(

        @Schema(description = "The carton was scanned at the bin (true) or not found there (false)")
        @NotNull(message = "found is required")
        Boolean found,

        @Schema(description = "The LPN scanned; required when found, absent when not found", example = "LPN-000123")
        @Size(max = 30, message = "lpn is at most 30 characters")
        String lpn
) {
}
