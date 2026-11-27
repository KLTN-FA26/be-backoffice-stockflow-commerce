package com.stockflow.assistant.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** A personal token to make for one MCP client of mine. */
public record CreateMcpTokenRequest(
        @Schema(description = "What I call the client, to tell my tokens apart; 1 to 80 characters",
                example = "Claude Code on my laptop")
        @NotBlankTrimmed @Size(max = 80) String name,
        @Schema(description = "Days the token lives, 1 to 90; 30 when absent", example = "30")
        @Min(1) @Max(90) Integer expiresInDays) {
}
