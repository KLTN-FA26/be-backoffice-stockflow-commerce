package com.stockflow.assistant.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * What {@code tools/list} answers: the tools the caller may use, in one page, by name.
 */
public record McpListToolsResult(List<Tool> tools) {

    /**
     * One tool as the chat's model is told about it.
     *
     * @param inputSchema the JSON Schema the tool declares, exactly as the Claude adapter sends it: an object whose
     *                    every property is required and that allows no other
     */
    public record Tool(
            @Schema(example = "findOrder") String name,
            String description,
            Map<String, Object> inputSchema,
            Annotations annotations) {
    }

    /**
     * Hints a client may show or act on.
     *
     * @param readOnlyHint  true for every tool: none writes anything, and a proposal is only a proposal (design §8)
     * @param openWorldHint false: every tool reads this application's own data, never the web
     */
    public record Annotations(boolean readOnlyHint, boolean openWorldHint) {
    }
}
