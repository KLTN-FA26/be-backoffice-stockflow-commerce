package com.stockflow.identity.api;

import java.util.List;

/**
 * Replace everything a role grants.
 *
 * @param expectedVersion the {@code version} of the matrix the editor loaded; a save against a role
 *                        someone else has changed since is refused, not merged
 * @param permissions     the complete new set, as {@code resource:ACTION} codes. The whole set, not a
 *                        diff: the screen shows the whole matrix, and a diff sent against a matrix
 *                        that has moved on would apply to state the user never saw.
 */
public record UpdateRolePermissionsCommand(String roleCode, long expectedVersion, List<String> permissions) {

    public UpdateRolePermissionsCommand {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }
}
