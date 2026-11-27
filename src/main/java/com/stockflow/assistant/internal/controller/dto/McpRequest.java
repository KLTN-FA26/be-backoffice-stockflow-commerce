package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One JSON-RPC 2.0 message posted to the MCP endpoint, once it has been checked: a request when it has an id, a
 * notification when it has none.
 *
 * <p>The endpoint reads the body itself, bounded in size, and builds this from what it read: a binding of the body
 * to this record would hold an unbounded body in memory first, and could not tell a missing {@code id} (a
 * notification) from one set to null (an invalid request).</p>
 *
 * <p>The two trees are documented as free-form values ({@code implementation = Object.class}): springdoc would
 * otherwise describe the {@code JsonNode} class itself, flag by flag.</p>
 *
 * @param id     a string or an integer, echoed in the response; null on a notification. A tree, so that it goes back
 *               exactly as it came, a number as a number and a string as a string
 * @param params the method's parameters; an empty object when the message carries none
 */
@Schema(description = "A JSON-RPC 2.0 request or notification of the Model Context Protocol")
public record McpRequest(
        @Schema(description = "Always 2.0", example = "2.0") String jsonrpc,
        @Schema(implementation = Object.class, example = "1",
                description = "A string or an integer; absent on a notification, which is answered 202 with no body")
        JsonNode id,
        @Schema(description = "initialize, ping, tools/list or tools/call", example = "tools/call") String method,
        @Schema(implementation = Object.class, description = "The method's parameters, an object",
                example = "{\"name\":\"findOrder\",\"arguments\":{\"orderNumber\":\"SO-20261028-000123\"}}")
        JsonNode params) {
}
