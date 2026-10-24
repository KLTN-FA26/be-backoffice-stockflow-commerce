package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** The assistant's answer to one question. */
public record AnswerResponse(
        UUID messageId,
        @Schema(description = "Markdown; render with HTML escaped and never follow links automatically")
        String answer,
        @Schema(description = "Every tool run for this answer, in order: the sources of its figures")
        List<ToolCallResponse> toolCalls,
        @Schema(description = "The answer hit the output limit and may be incomplete") boolean cutShort,
        Usage usage) {

    /** Tokens across every model call the answer took. */
    public record Usage(long inputTokens, long cachedInputTokens, long outputTokens) {
    }
}
