package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A conversation summed up for the colleague who takes it over (contract §1.3). */
public record ConversationSummaryResponse(
        DraftResponse summary,
        @Schema(description = "What the customer asked for or about") List<String> customerAsks,
        @Schema(description = "What the staff side promised") List<String> promisesMade,
        @Schema(description = "What is still unanswered") List<String> openQuestions,
        @Schema(description = "The customer's orders the conversation is about") List<String> orderNumbers) {
}
