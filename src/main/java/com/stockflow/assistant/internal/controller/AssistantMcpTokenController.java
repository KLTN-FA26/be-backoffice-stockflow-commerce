package com.stockflow.assistant.internal.controller;

import com.stockflow.assistant.internal.controller.dto.CreateMcpTokenRequest;
import com.stockflow.assistant.internal.controller.dto.McpTokenResponse;
import com.stockflow.assistant.internal.controller.dto.McpTokensRevokedResponse;
import com.stockflow.assistant.internal.domain.McpToken;
import com.stockflow.assistant.internal.service.McpTokens;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * My personal tokens for the assistant's MCP endpoint (design §7, ADR-0013): made for one AI client, shown once,
 * revoked on their own.
 *
 * <p><b>The sign-in token only.</b> These endpoints are not among a personal token's paths, so the API chain refuses
 * one here with 401: a client that holds a token cannot make another and outlive its expiry or revocation.</p>
 *
 * <p><b>Guard:</b> {@code assistant-mcp:CREATE} with scope OWN, the MCP endpoint's own, and the back office first. A
 * token is always its maker's: another user's is not found, whatever the caller's scope.</p>
 *
 * <p><b>Made only just after signing in</b> ({@link McpTokens#RECENT_SIGN_IN}): a sign-in token is issued at sign-in
 * and never renewed, so its {@code iat} says when the user last proved who they are. A script in the back office's
 * page can call this endpoint with the user's session; it cannot make it recent. Older: 403
 * {@code RECENT_SIGN_IN_REQUIRED}, and the page asks the user to sign in again.</p>
 *
 * <p><b>For an administrator</b>, under the user screen's own guards ({@code identity-users} READ and UPDATE): a user's
 * tokens, and revoking all of them at once, for a lost laptop or a leaver, while the account itself stays.</p>
 *
 * <p><b>Never replayed.</b> The answer that makes a token holds it in plain text, and the idempotency filter would
 * store a response it replays for a day, so {@code Idempotency-Key} is refused with 400 before anything is made. A
 * retried request makes a second token, which its owner sees in the list and revokes.</p>
 *
 * <p>Off with the MCP endpoint ({@value McpTokens#ENABLED}): a token would open nothing.</p>
 */
@RestController
@RequestMapping("/api/v1/assistant/mcp-tokens")
@Tag(name = "Assistant")
@PermissionResource(
        code = AssistantResources.MCP,
        group = "Assistant",
        label = "AI assistant for other AI clients (MCP)",
        apiPath = "/api/v1/assistant/mcp",
        actions = {Action.CREATE})
@ConditionalOnProperty(name = McpTokens.ENABLED, havingValue = "true")
class AssistantMcpTokenController {

    /** Tokens one user may make a minute: making them is rare, and a loop making them is not a client. */
    static final int MADE_PER_MINUTE = 5;

    /** {@code IdentityResources.USERS}, copied by value: the user screen's resource, internal to identity. */
    static final String USERS = "identity-users";

    private final McpTokens tokens;
    private final Clock clock;

    AssistantMcpTokenController(McpTokens tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Make a personal token for one MCP client of mine",
            description = "The answer holds the token, sfmcp_ and 43 characters, this once: it is stored as a hash. "
                    + "It opens POST /api/v1/assistant/mcp only, with my permissions as they stand at each call, for "
                    + "1 to 90 days (30 by default). At most 10 live tokens: 409 ASSISTANT_MCP_TOKEN_LIMIT. "
                    + "Idempotency-Key is refused (400): a stored answer would hold the token. Needs "
                    + "assistant-mcp:CREATE and the sign-in token; a personal token is refused here (401).")
    @RequiresPermission(resource = AssistantResources.MCP, action = Action.CREATE, scope = DataScope.OWN)
    @RateLimit(limit = MADE_PER_MINUTE, perSeconds = 60, key = RateLimit.Key.USER, bucket = "assistant-mcp-tokens")
    public ApiResponse<McpTokenResponse> create(@AuthenticatedUser CurrentUser user,
                                                @Parameter(hidden = true)
                                                @RequestHeader(name = IdempotencyKeys.HEADER, required = false)
                                                String idempotencyKey,
                                                @Valid @RequestBody CreateMcpTokenRequest request) {
        ScopeGuards.requireBackOffice(user);
        if (idempotencyKey != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, IdempotencyKeys.HEADER + " is not accepted "
                    + "here: the answer holds the token, and a stored answer would keep it in plain text for a day");
        }
        Instant signedIn = signedInAt(SecurityContextHolder.getContext().getAuthentication());
        if (signedIn == null || signedIn.isBefore(clock.instant().minus(McpTokens.RECENT_SIGN_IN))) {
            throw new BusinessException(ErrorCode.RECENT_SIGN_IN_REQUIRED, "Sign in again to make an MCP token: "
                    + "the sign-in must be at most " + McpTokens.RECENT_SIGN_IN.toMinutes() + " minutes old");
        }
        McpTokens.Created created = tokens.create(user, request.name(), request.expiresInDays());
        return ApiResponse.ok(toResponse(created.view(), created.token(), clock.instant()));
    }

    /** When the sign-in token was issued, which is when its user signed in; null for any other authentication. */
    static Instant signedInAt(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwt
                && !CurrentUserProvider.actor().isPresent() ? jwt.getToken().getIssuedAt() : null;
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "A user's MCP tokens, live ones first, without the tokens themselves (administrators)")
    @RequiresPermission(resource = USERS, action = Action.READ)
    public ApiResponse<List<McpTokenResponse>> tokensOf(@AuthenticatedUser CurrentUser user,
                                                        @PathVariable UUID userId) {
        ScopeGuards.requireBackOffice(user);
        Instant now = clock.instant();
        return ApiResponse.ok(tokens.tokensOf(userId).stream().map(token -> toResponse(token, null, now)).toList());
    }

    @PostMapping("/users/{userId}/revocation")
    @Operation(summary = "Revoke every live MCP token of a user (administrators); the account and its sign-in stay",
            description = "Each client using one is refused (401) at its next call. Answers how many were live.")
    @RequiresPermission(resource = USERS, action = Action.UPDATE)
    public ApiResponse<McpTokensRevokedResponse> revokeAllOf(@AuthenticatedUser CurrentUser user,
                                                             @PathVariable UUID userId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(new McpTokensRevokedResponse(tokens.revokeAllOf(userId)));
    }

    @GetMapping
    @Operation(summary = "My MCP tokens, newest first, without the tokens themselves")
    @RequiresPermission(resource = AssistantResources.MCP, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<List<McpTokenResponse>> mine(@AuthenticatedUser CurrentUser user) {
        ScopeGuards.requireBackOffice(user);
        Instant now = clock.instant();
        return ApiResponse.ok(tokens.list(user).stream().map(token -> toResponse(token, null, now)).toList());
    }

    @PostMapping("/{tokenId}/revocation")
    @Operation(summary = "Revoke one of my MCP tokens; the next call with it is refused (401)",
            description = "Revoking it again changes nothing. 404 when the token is not mine.")
    @RequiresPermission(resource = AssistantResources.MCP, action = Action.CREATE, scope = DataScope.OWN)
    public ApiResponse<McpTokenResponse> revoke(@AuthenticatedUser CurrentUser user, @PathVariable UUID tokenId) {
        ScopeGuards.requireBackOffice(user);
        return ApiResponse.ok(toResponse(tokens.revoke(user, tokenId), null, clock.instant()));
    }

    static McpTokenResponse toResponse(McpToken token, String secret, Instant now) {
        String status = token.revokedAt() != null ? "REVOKED" : token.live(now) ? "LIVE" : "EXPIRED";
        return new McpTokenResponse(token.id(), token.name(), token.hint(), status, token.createdAt(),
                token.expiresAt(), token.lastUsedAt(), token.revokedAt(), secret);
    }
}
