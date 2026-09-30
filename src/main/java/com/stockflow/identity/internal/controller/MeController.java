package com.stockflow.identity.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.security.AuthenticatedUser;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.identity.internal.controller.dto.MyPermissionsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the signed-in user may do, so the frontend can show and hide menus and buttons (SCRUM-385).
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

    @GetMapping("/permissions")
    @Operation(summary = "The signed-in user's roles and effective permissions; refetch after a 403")
    public ApiResponse<MyPermissionsResponse> permissions(@AuthenticatedUser CurrentUser user) {
        return ApiResponse.ok(new MyPermissionsResponse(
                user.roles().stream().map(Role::authority).sorted().toList(),
                user.permissions().stream().map(PermissionCode::toString).sorted().toList(),
                user.scope()));
    }
}
