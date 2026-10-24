package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One message of a conversation as the panel shows it: a question, or an answer with the tools behind it. */
public record TranscriptEntryResponse(
        UUID messageId,
        int sequence,
        @Schema(description = "USER for a question, ASSISTANT for an answer") String role,
        String text,
        Instant at,
        @Schema(description = "The tools run for this answer, in order; empty for a question")
        List<ToolCallResponse> toolCalls,
        @Schema(description = "The answer hit the output limit and may be incomplete") boolean cutShort,
        @Schema(description = "Business-rule anchors the answer cites") List<String> citations,
        @Schema(description = "Purchase orders the panel may open pre-filled") List<ProposalResponse> proposals) {
}
