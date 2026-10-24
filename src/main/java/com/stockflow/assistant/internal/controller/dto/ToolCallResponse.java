package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One tool the assistant ran for an answer: what the panel shows next to it as its source. */
public record ToolCallResponse(
        @Schema(example = "findLowStock") String tool,
        @Schema(description = "OK, DENIED (the user lacks the permission), NOT_FOUND, INVALID or FAILED")
        String outcome) {
}
