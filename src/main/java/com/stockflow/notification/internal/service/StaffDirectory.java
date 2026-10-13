package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Role;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The e-mail addresses of the active staff holding a role: who a staff notice goes to when the notice names
 * a team ("Sales is notified", "alert purchasing", "the Warehouse Manager resolves") rather than a person.
 * Read through {@code identity :: api}, the address book; never a role's grants, which are not addresses.
 */
@Component
class StaffDirectory {

    private static final int PAGE = 100;

    private final IdentityService identity;

    StaffDirectory(IdentityService identity) {
        this.identity = identity;
    }

    /** Distinct, non-blank addresses of ACTIVE accounts holding {@code role}, in the identity module's order. */
    List<String> activeEmails(Role role) {
        Set<String> emails = new LinkedHashSet<>();
        int page = 0;
        PageResponse<StaffUser> users;
        do {
            users = identity.listUsers(new ListUsersQuery(null, "ACTIVE", role.name(), false, page++, PAGE, null));
            for (StaffUser user : users.items()) {
                if ("ACTIVE".equals(user.status()) && user.email() != null && !user.email().isBlank()) {
                    emails.add(user.email().strip());
                }
            }
        } while (users.hasNext());
        return new ArrayList<>(emails);
    }
}
