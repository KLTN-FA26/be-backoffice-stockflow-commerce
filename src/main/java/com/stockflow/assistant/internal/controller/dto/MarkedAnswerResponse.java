package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An answer someone gave a verdict, for the System Admin's review (contract §5.1). Text as stored, customers by the
 * aliases the model saw (KH-1); who rated by role only.
 */
public record MarkedAnswerResponse(
        UUID feedbackId,
        @Schema(description = "The answer rated") UUID messageId,
        @Schema(allowableValues = {"UP", "DOWN"}) String verdict,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "What the rater wrote, if anything", nullable = true) String comment,
        @Schema(description = "When the verdict was last given") Instant givenAt,
        @Schema(description = "The rater's roles when they rated; empty for a verdict given before roles were "
                + "recorded") List<String> raterRoles,
        @Schema(description = "The warehouses the rater was limited to; empty when not limited or not recorded")
        List<String> raterWarehouses,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "The question answered", nullable = true) String question,
        String answer,
        Instant answeredAt,
        @Schema(description = "The model that wrote the answer") String model,
        @Schema(description = "Every tool call made for the answer, in order") List<ToolCall> tools,
        @Schema(description = "The answer as a case of src/test/resources/assistant/golden-set.json, to paste and "
                + "complete: useCase is TODO and facts is empty, for the reviewer to fill with what a right answer "
                + "holds")
        JsonNode goldenCase) {

    public record ToolCall(
            @Schema(description = "The model turn that asked for it: calls of one turn were asked for together")
            int step,
            String tool,
            @Schema(description = "The arguments the model gave") JsonNode arguments,
            @Schema(allowableValues = {"OK", "DENIED", "NOT_FOUND", "INVALID", "FAILED"}) String outcome) {
    }
}
