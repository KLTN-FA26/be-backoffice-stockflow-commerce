package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.SessionValidator;
import com.stockflow.identity.api.ChangePasswordCommand;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.internal.controller.dto.ChangePasswordRequest;
import com.stockflow.identity.internal.controller.dto.SessionResponse;
import com.stockflow.identity.internal.controller.dto.SessionsEndedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What the signed-in user does to their own sessions and credentials. No {@code @RequiresPermission}:
 * these are not resources an administrator grants, every authenticated user has them, and each call
 * only ever touches the caller's own account (the id comes from the token, never from the request).
 */
@RestController
@RequestMapping("/api/v1/identity/auth")
@Tag(name = "Auth", description = "Sign-in sessions and password")
class SessionController {

    private final IdentityService identityService;

    SessionController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign this device out; its token stops working immediately")
    public ApiResponse<Void> logout(@AuthenticatedUser CurrentUser user, @AuthenticationPrincipal Jwt jwt) {
        identityService.logout(user.userId(), sessionId(jwt));
        return ApiResponse.ok(null);
    }

    @PostMapping("/logout-others")
    @Operation(summary = "Sign out every other device, keeping this one")
    public ApiResponse<SessionsEndedResponse> logoutOthers(@AuthenticatedUser CurrentUser user,
                                                           @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new SessionsEndedResponse(
                identityService.logoutOtherSessions(user.userId(), sessionId(jwt))));
    }

    @GetMapping("/sessions")
    @Operation(summary = "The devices currently signed in to this account, newest first")
    public ApiResponse<PageResponse<SessionResponse>> sessions(@AuthenticatedUser CurrentUser user,
                                                               @AuthenticationPrincipal Jwt jwt,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(identityService
                .listSessions(user.userId(), sessionId(jwt), page, size == null ? Pages.DEFAULT_PAGE_SIZE : size)
                .map(s -> new SessionResponse(s.sessionId(), s.issuedAt(), s.expiresAt(),
                        s.clientAddress(), s.userAgent(), s.current())));
    }

    /**
     * Keyed by user, not address: the caller is already authenticated, so an attacker holding a
     * stolen token and guessing the current password is the case to slow down, and one account's
     * attempts must not eat another's allowance.
     */
    @PutMapping("/password")
    @Operation(summary = "Change the password, optionally signing out every other device")
    @RateLimit(limit = 5, perSeconds = 300, key = RateLimit.Key.USER)
    public ApiResponse<SessionsEndedResponse> changePassword(@AuthenticatedUser CurrentUser user,
                                                             @AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody ChangePasswordRequest request) {
        return ApiResponse.ok(new SessionsEndedResponse(identityService.changePassword(
                new ChangePasswordCommand(user.userId(), sessionId(jwt), request.currentPassword(),
                        request.newPassword(), request.logoutOtherDevices()))));
    }

    /** Null for a token issued before sessions existed: it has no session to end or to keep. */
    private static UUID sessionId(Jwt jwt) {
        String claim = jwt == null ? null : jwt.getClaimAsString(SessionValidator.SESSION_CLAIM);
        return claim == null ? null : UUID.fromString(claim);
    }
}
