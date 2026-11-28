package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A person's verdict on one answer; a second one on the same answer replaces the first. */
public record FeedbackRequest(
        @Schema(description = "UP: right and of use. DOWN: wrong, refused, or no help", allowableValues = {"UP", "DOWN"},
                example = "DOWN")
        @NotBlank @Pattern(regexp = "^(UP|DOWN)$") String verdict,
        @Schema(description = "Why, in the user's words; optional, at most 500 characters",
                example = "Số tồn của kho EVN không đúng")
        @Size(max = 500) String reason) {
}
