package com.stockflow.assistant.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** One question to the assistant. */
public record AskRequest(
        @Schema(description = "The question, in Vietnamese or English; 1 to 4000 characters",
                example = "Mã nào đang dưới điểm đặt hàng?")
        @NotBlankTrimmed @Size(max = 4000) String text) {
}
