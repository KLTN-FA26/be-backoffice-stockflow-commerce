package com.stockflow.identity.api;

import java.util.List;
import java.util.UUID;

/**
 * A staff member who can be given work in a warehouse (SCRUM-466): the least that a screen choosing an
 * assignee needs. No e-mail, phone or status — the caller asked for active people in one warehouse and
 * user management, which needs more, is a separate permission.
 *
 * @param displayName full name, or the username when none was given
 * @param roles       the requested roles the user holds, sorted
 */
public record AssignableUser(UUID userId, String displayName, List<String> roles) {

    public AssignableUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
