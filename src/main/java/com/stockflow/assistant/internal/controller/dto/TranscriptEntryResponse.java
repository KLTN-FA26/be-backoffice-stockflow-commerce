package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
        @Schema(description = "Purchase orders the panel may open pre-filled") List<ProposalResponse> proposals,
        @Schema(description = "The rows the lookups returned, as they were when the answer was given (see at)")
        List<TableResponse> tables,
        @Schema(description = "Up to three follow-up questions, under the caller's permissions of now; empty for a "
                + "question")
        List<String> suggestions,
        @Schema(description = "Numbers the answer states that nothing it was given holds, as written")
        List<String> unverifiedNumbers,
        @Schema(description = "Codes the answer writes that nothing it was given holds, as written")
        List<String> unverifiedCodes,
        @Schema(description = "Lookups whose data held typed text that reads like an instruction to the assistant")
        List<String> instructionLike,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "The verdict the caller gave this answer; null for none, and for a question",
                allowableValues = {"UP", "DOWN"}, nullable = true)
        String feedback) {
}
