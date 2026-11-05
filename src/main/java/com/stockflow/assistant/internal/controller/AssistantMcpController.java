package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.McpRequest;
import com.stockflow.assistant.internal.controller.dto.McpResponse;
import com.stockflow.assistant.internal.service.McpTokens;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * The Model Context Protocol endpoint (contract C8, design §7): another AI client, such as Claude Desktop or an IDE
 * agent, uses the assistant's read tools under the signed-in user's own permissions, without this application's agent
 * loop. {@link McpServer} speaks the protocol; this class is the HTTP side of its Streamable HTTP transport.
 *
 * <p><b>Two credentials, one user.</b> The sign-in JWT every endpoint takes, or a personal token made for this client
 * ({@code sfmcp_...}, ADR-0013), which the API chain accepts on this path only. Either way the request carries the
 * user's grants as they stand now. Without one the answer is 401 before anything here runs, and with security switched
 * off for local work {@code @AuthenticatedUser} still refuses a request with no user, so in no profile do the tools
 * answer an anonymous client. The guard is {@code assistant-mcp:CREATE} with scope OWN and the back office first:
 * held by SYSTEM_ADMIN only until the business grants it to a role (V20261105001100, AQ-17). Like the chat's it opens
 * the endpoint and grants no data: every tool still checks the caller (design §5).</p>
 *
 * <p><b>Not from a browser page.</b> A request with an {@code Origin} the shared CORS policy does not allow is 403
 * with a JSON-RPC error before authentication ({@link McpOriginFilter}), as the transport requires of a server against
 * DNS rebinding. A page has no token to send anyway: neither credential is a cookie.</p>
 *
 * <p><b>One budget with the chat.</b> Every request draws on the rate-limit bucket of the two ways of asking
 * ({@link AssistantResources#QUESTIONS_BUCKET}), so a user gains no second allowance by switching clients; a client's handshake
 * (initialize, the initialized notification, tools/list) costs three of it. The daily question cap does not apply: it
 * bounds what this application pays its model provider, and nothing here calls the model.</p>
 *
 * <p><b>POST only.</b> A GET would open a stream for messages the server sends on its own; there are none, so it falls
 * to Spring's 405, which the transport allows. No {@code Idempotency-Key} is needed: the idempotency filter lets a
 * request without one through untouched, and every method here only reads, so a retry does no harm.</p>
 *
 * <p><b>Off unless asked for.</b> The endpoint exists only with {@value #ENABLED} true (environment variable
 * {@code ASSISTANT_MCP_ENABLED}; the local profile sets it): it calls no model of this application's, but what a tool
 * answers here leaves for whichever model provider the client uses, which the business does not choose (design §13
 * AQ-01, AQ-17). Without it the path answers 404, and turning it off again needs no release.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant")
@Tag(name = "Assistant")
@ConditionalOnProperty(name = AssistantMcpController.ENABLED, havingValue = "true")
class AssistantMcpController {

    /** The property that opens the endpoint when true, its personal tokens with it. */
    static final String ENABLED = McpTokens.ENABLED;

    private final McpServer server;

    AssistantMcpController(McpServer server) {
        this.server = server;
    }

    @PostMapping(path = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The assistant's read tools for another AI client, over the Model Context Protocol",
            description = "MCP Streamable HTTP with JSON responses: one JSON-RPC 2.0 message per POST (initialize, "
                    + "ping, tools/list, tools/call; a notification is answered 202 with no body). Protocol "
                    + "2025-11-25, 2025-06-18 or 2025-03-26. Needs assistant-mcp:CREATE and a back-office account, "
                    + "with the bearer token of POST /api/v1/identity/auth/login or a personal token of POST "
                    + "/api/v1/assistant/mcp-tokens. tools/list holds the tools the caller may use, each with its "
                    + "outputSchema from 2025-06-18; tools/call runs one under the caller's own permissions and "
                    + "warehouses, as the assistant's chat does, and answers its result as text, and as "
                    + "structuredContent from 2025-06-18; isError true for any outcome but data (NOT_PERMITTED, "
                    + "NOT_FOUND, VALIDATION_FAILED, ...). Shares the rate limit of the questions to the assistant: 10 "
                    + "requests a minute per user. Stateless: no Mcp-Session-Id. GET is 405: the server sends nothing "
                    + "on its own. 400 or 413 with a JSON-RPC error for a body that is not one JSON-RPC message of at "
                    + "most 64 KiB; 403 for an Origin the CORS policy does not allow.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = McpRequest.class))))
    @RequiresPermission(resource = AssistantResources.MCP, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = AssistantResources.QUESTIONS_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER,
            bucket = AssistantResources.QUESTIONS_BUCKET)
    public ResponseEntity<McpResponse> message(@AuthenticatedUser CurrentUser user, HttpServletRequest request)
            throws IOException {
        ScopeGuards.requireBackOffice(user);
        McpServer.Reply reply = server.handle(request.getInputStream(), request.getContentLengthLong(),
                request.getHeader(McpServer.PROTOCOL_VERSION_HEADER), user);
        return ResponseEntity.status(reply.status()).body(reply.body());
    }
}
