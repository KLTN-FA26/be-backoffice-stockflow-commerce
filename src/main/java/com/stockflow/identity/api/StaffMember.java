package com.stockflow.identity.api;

import java.util.List;
import java.util.UUID;

/**
 * An active staff member as a colleague picks them (to mention in a note, say): who, what they are called and what
 * roles they hold. No e-mail, phone or status - user management, which shows those, is a separate permission.
 *
 * @param displayName full name, or the username when none was given
 * @param roles       the roles held, by code
 */
public record StaffMember(UUID userId, String displayName, List<Role> roles) {

    public StaffMember {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** A role held: its code, and the name the role list shows. */
    public record Role(String code, String name) {
    }
}
