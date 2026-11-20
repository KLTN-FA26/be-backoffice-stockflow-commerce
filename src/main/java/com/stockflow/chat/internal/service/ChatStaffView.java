package com.stockflow.chat.internal.service;

import java.util.List;
import java.util.UUID;

/**
 * A colleague an internal note may mention (16 §4.6 step 16: the accountant, the warehouse, the Sales Manager), for the
 * mention picker. Any active staff member, not only those who answer customers ({@link ChatAgentView}).
 *
 * @param role     the code of the first role held, by code; null for an account that holds none. A shortcut for the
 *                 picker's label - {@code roles} has them all
 * @param roleName that role's name as the role list shows it
 * @param roles    every role held, by code
 */
public record ChatStaffView(UUID id, String name, String role, String roleName, List<Role> roles) {

    /** A role held: its code and its name. */
    public record Role(String code, String name) {
    }
}
