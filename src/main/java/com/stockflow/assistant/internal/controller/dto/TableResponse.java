package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Rows one lookup returned, shown under the answer exactly as the tool returned them (design §7). The answer above
 * says what matters in them; it does not repeat them.
 */
public record TableResponse(
        @Schema(description = "Which list it is, as tool.field under an answer, the section's key on the today card: "
                + "what the panel labels the table by. Two calls of one tool give two tables with the same key",
                example = "findLowStock.skus")
        String key,
        @Schema(example = "findLowStock") String tool,
        @Schema(description = "The keys of each row, in display order: the tool's field names, a dotted path for a "
                + "field of a nested record (hold.reason). The panel labels them",
                example = "[\"sku\", \"usableOnHand\", \"availableToPromise\", \"reorderPoint\"]")
        List<String> columns,
        @Schema(description = "Each row maps a column to a text, a number or true/false; a value the tool did not "
                + "return is absent under an answer and null on the today card. Text can be what a person typed: "
                + "render it as text, never as HTML")
        List<Map<String, Object>> rows,
        @Schema(description = "How many rows there are in all, as the tool counted them", example = "23") long total,
        @Schema(description = "More rows exist than are listed") boolean truncated) {
}
