package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What {@code tools/call} answers: the tool's result as one text block, exactly what the chat's model receives, and
 * for a client of 2025-06-18 or later the same result as JSON.
 *
 * @param structuredContent the result as a JSON object, valid against the tool's {@code outputSchema}; only with data
 *                          (isError false) and only when the tool has a schema. The text block still carries it, as
 *                          the protocol asks, for a client that reads text only
 * @param isError           true for every outcome but data (DENIED, NOT_FOUND, INVALID, FAILED); the text then starts
 *                          with the code, such as {@code NOT_PERMITTED: Missing permission: inventory-stock-items:READ}.
 *                          Named explicitly, so the wire name does not depend on how Jackson names a boolean accessor
 *                          starting with "is"
 */
public record McpCallToolResult(
        List<Content> content,
        JsonNode structuredContent,
        @JsonProperty("isError") boolean isError) {

    /**
     * @param type always {@code text}
     * @param text the tool's JSON result, or its error line
     */
    public record Content(
            @Schema(example = "text") String type,
            @Schema(example = "{\"orderNumber\":\"SO-20261028-000123\",\"status\":\"CONFIRMED\"}") String text) {
    }
}
