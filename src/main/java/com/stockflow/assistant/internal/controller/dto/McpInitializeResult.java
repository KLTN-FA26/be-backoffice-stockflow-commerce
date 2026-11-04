package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code initialize} answers: the protocol version both sides will speak, what this server offers, and how a
 * client's model should use it.
 *
 * @param instructions model-facing guidance a client may add to its own system prompt
 */
public record McpInitializeResult(
        @Schema(description = "The version asked for when this server speaks it, otherwise the newest it does",
                example = "2025-06-18")
        String protocolVersion,
        Capabilities capabilities,
        Implementation serverInfo,
        String instructions) {

    /** Tools only: no resources, prompts, logging or sampling. */
    public record Capabilities(Tools tools) {
    }

    /**
     * @param listChanged false: the list depends on the caller's grants, which a client learns by listing again, and
     *                    this server never sends a notification of its own
     */
    public record Tools(boolean listChanged) {
    }

    public record Implementation(
            @Schema(example = "stockflow-assistant") String name,
            @Schema(example = "StockFlow back-office assistant") String title,
            @Schema(example = "1.0.0-SNAPSHOT") String version) {
    }
}
