package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to one JSON-RPC 2.0 request of the MCP endpoint: a result or an error, never both.
 *
 * <p>Not wrapped in {@code ApiResponse}: an MCP client reads exactly a JSON-RPC response, and any other shape is a
 * protocol error to it. The platform's own refusals (401, 403, 429, 405) keep the platform's envelope, since they are
 * HTTP answers given before this endpoint runs.</p>
 *
 * @param id     the request's id; null, and still written, when it could not be read (JSON-RPC 2.0 section 5)
 * @param result the method's result; absent on an error
 * @param error  absent on a result
 */
@Schema(description = "A JSON-RPC 2.0 response: result or error, never both")
public record McpResponse(
        @Schema(description = "Always 2.0", example = "2.0") String jsonrpc,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(implementation = Object.class, nullable = true, example = "1",
                description = "The request's id, a string or an integer as it came; null when it could not be read")
        JsonNode id,
        @Schema(description = "initialize: the server's capabilities; ping: {}; tools/list: the tools this user may "
                + "call; tools/call: the tool's result as text, isError true for any outcome but data")
        Object result,
        RpcError error) {

    /**
     * A JSON-RPC error object.
     *
     * @param code -32700 the body is not JSON; -32600 not a valid JSON-RPC message (or too large, or an unsupported
     *             MCP-Protocol-Version); -32601 a method this server does not have; -32602 missing or invalid
     *             parameters, or a tool that does not exist
     */
    public record RpcError(
            @Schema(example = "-32601") int code,
            @Schema(example = "Method not found: resources/list") String message) {
    }
}
