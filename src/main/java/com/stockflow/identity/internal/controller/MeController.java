package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UserProfile;
import com.stockflow.identity.internal.controller.dto.MeResponse;
import com.stockflow.identity.internal.controller.dto.MyPermissionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Who the signed-in user is and what they may do, so the frontend can fill its header and show and
 * hide menus and buttons (SCRUM-384/385).
 *
 * <p>Tokens no longer carry permissions (ADR-0008), so this is how a client learns them. The answer
 * is read from the already-authenticated request — the same resolution every guarded endpoint uses
 * — so it cannot disagree with what the server will actually allow. No {@code @RequiresPermission}:
 * every signed-in user may ask about themselves, and nothing here names anyone else.</p>
 */
@RestController
@RequestMapping("/api/v1/identity/me")
@Tag(name = "Auth", description = "Sign-in sessions and password")
class MeController {

    private final IdentityService identityService;
    private final PermissionCatalog catalog;
    private final boolean securityEnabled;

    MeController(IdentityService identityService, PermissionCatalog catalog,
                 @Value("${stockflow.security.enabled:true}") boolean securityEnabled) {
        this.identityService = identityService;
        this.catalog = catalog;
        this.securityEnabled = securityEnabled;
    }

    @GetMapping
    @Operation(summary = "The signed-in user's profile and roles; the login response carries only a token")
    public ApiResponse<MeResponse> me(@AuthenticatedUser CurrentUser user) {
        UserProfile profile = identityService.profile(user.userId());
        return ApiResponse.ok(new MeResponse(profile.userId(), profile.username(), profile.email(),
                profile.fullName(), profile.status(), roles(user), profile.lastLoginAt()));
    }

    /**
     * With security switched off ({@code local} and {@code test} profiles) no token is read, so there
     * is no caller to describe — yet every endpoint is open. Answering 401 there made the frontend
     * hide every screen while the backend would have served all of them; answering with the whole
     * catalog tells it the truth about that environment. With security on, an unauthenticated call
     * never reaches this method: the filter chain answers 401 first.
     */
    @GetMapping("/permissions")
    @Operation(summary = "The signed-in user's roles and effective permissions; refetch after a 403")
    public ApiResponse<MyPermissionsResponse> permissions(@AuthenticatedUser Optional<CurrentUser> user) {
        if (user.isPresent()) {
            return ApiResponse.ok(new MyPermissionsResponse(roles(user.get()),
                    user.get().permissions().stream().map(PermissionCode::toString).sorted().toList(),
                    user.get().scope()));
        }
        if (securityEnabled) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(new MyPermissionsResponse(List.of(),
                catalog.allPermissions().stream().map(PermissionCode::toString).sorted().toList(),
                DataScope.ALL));
    }

    private static List<String> roles(CurrentUser user) {
        return user.roles().stream().map(Role::authority).sorted().toList();
    }
}
