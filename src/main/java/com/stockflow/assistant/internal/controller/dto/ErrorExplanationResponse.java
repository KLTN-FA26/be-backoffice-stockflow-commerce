package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Why a business refusal happened and what to do next (contract §4.1). Worked out by code, no model. */
public record ErrorExplanationResponse(
        @Schema(description = "The code explained", example = "LOAD_HANDOVER_BLOCKED") String errorCode,
        @Schema(description = "The code's message in the caller's language, as the refusal showed it") String message,
        @Schema(description = "The kltn-docs rules behind the refusal, from the corpus the assistant indexes; empty "
                + "when none is mapped: show the message alone, never \"no rule applies\"")
        List<Rule> rules,
        @Schema(description = "One to three things to do now, in Vietnamese; empty when none is mapped")
        List<String> nextSteps) {

    /** One rule, whole. */
    public record Rule(
            @Schema(description = "The rule as the analysis cites it: chapter and rule", example = "15 BR-08")
            String ruleId,
            @Schema(description = "The kltn-docs chapter, by its snapshot name", example = "15-payment")
            String chapter,
            @Schema(description = "The rule's title: its id, its name if it has one, and the chapter's name")
            String title,
            @Schema(description = "The rule's text, plain (Markdown emphasis and links removed), Vietnamese")
            String text,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @Schema(description = "A back-office page that shows the rule; always null today (no screen shows the "
                    + "analysis)", nullable = true)
            String href) {
    }
}
