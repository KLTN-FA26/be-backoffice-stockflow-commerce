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
        Usage usage,
        @Schema(description = "Business-rule anchors the answer cites, each one returned by its own rule search",
                example = "[\"BR-RMA-002\"]")
        List<String> citations,
        @Schema(description = "Purchase orders the panel may open pre-filled; nothing has been created")
        List<ProposalResponse> proposals,
        @Schema(description = "The rows the lookups returned, to show under the answer: the answer sums them up and "
                + "does not repeat them")
        List<TableResponse> tables,
        @Schema(description = "Up to three questions to offer as one-click follow-ups, in the caller's language, each "
                + "one the caller's permissions let the assistant answer",
                example = "[\"Tồn kho của EVAL-LID-90 ở từng kho là bao nhiêu?\"]")
        List<String> suggestions,
        @Schema(description = "Numbers the answer states, as written, that no lookup, question or context of the "
                + "conversation holds: show them as not found in the looked-up data. Advisory; the answer is "
                + "unchanged", example = "[\"128,4 triệu\"]")
        List<String> unverifiedNumbers,
        @Schema(description = "Codes the answer writes (a SKU, an order, lot, bin or carton code), as written, that no "
                + "lookup, question or context of the conversation holds: show them as not found in the looked-up "
                + "data. Advisory; the answer is unchanged", example = "[\"CUP-A\"]")
        List<String> unverifiedCodes,
        @Schema(description = "The trace of this answer, for opening it in Jaeger; only where "
                + "stockflow.assistant.expose-trace-id is on (a developer's machine), null elsewhere",
                example = "4bf92f3577b34da6a3ce929d0e0e4736")
        String traceId) {

    /** Tokens across every model call the answer took. */
    public record Usage(long inputTokens, long cachedInputTokens, long outputTokens) {
    }
}
