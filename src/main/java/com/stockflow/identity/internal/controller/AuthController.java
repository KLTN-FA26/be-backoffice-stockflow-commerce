package com.stockflow.identity.internal.controller;

import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.LoginCommand;
import jakarta.servlet.http.HttpServletRequest;
import com.stockflow.identity.internal.controller.dto.AuthTokenResponse;
import com.stockflow.identity.internal.controller.dto.LoginRequest;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff sign-in (SCRUM-378/WBS 3.19.7). No {@code @PermissionResource}: there is no permission to
 * hold before you have a token, so nothing here is guarded by {@code @RequiresPermission} — the
 * exemption instead lives in {@code ResourceServerSecurityConfig}'s {@code permitAll()} matcher.
 *
 * <p>{@code @RateLimit(key = IP)}, not {@code USER}: there is no authenticated caller yet, and
 * keying by user would let an attacker lock a victim out by failing their password five times —
 * see {@link RateLimit}'s own javadoc, which uses this exact endpoint as its example.</p>
 */
@RestController
@RequestMapping("/api/v1/identity/auth")
@Tag(name = "Auth", description = "Staff sign-in")
class AuthController {

    private final IdentityService identityService;
    private final AuthWebMapper mapper;

    AuthController(IdentityService identityService, AuthWebMapper mapper) {
        this.identityService = identityService;
        this.mapper = mapper;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with a username and password, get a bearer token")
    @RateLimit(limit = 5, perSeconds = 60, key = RateLimit.Key.IP)
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest http) {
        return ApiResponse.ok(mapper.toResponse(identityService.login(new LoginCommand(
                request.username(), request.password(), http.getRemoteAddr(),
                http.getHeader("User-Agent")))));
    }
}
