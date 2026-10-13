package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Role;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.ListUsersQuery;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffUser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who a staff notice goes to when it names a team ("Sales is notified", "alert purchasing", "the Warehouse Manager
 * resolves") rather than a person: the ACTIVE accounts holding the role, with their id (the in-app inbox) and e-mail
 * (best-effort mail). Read through {@code identity :: api}, the address book; never a role's grants.
 */
@Component
class StaffDirectory {

    private static final int PAGE = 100;

    private final IdentityService identity;

    StaffDirectory(IdentityService identity) {
        this.identity = identity;
    }

    /** Every ACTIVE account holding {@code role}, each once, in the identity module's order; e-mail may be null. */
    List<StaffContact> activeStaff(Role role) {
        Map<UUID, StaffContact> staff = new LinkedHashMap<>();
        int page = 0;
        PageResponse<StaffUser> users;
        do {
            users = identity.listUsers(new ListUsersQuery(null, "ACTIVE", role.name(), false, page++, PAGE, null));
            for (StaffUser user : users.items()) {
                if ("ACTIVE".equals(user.status())) {
                    staff.putIfAbsent(user.userId(), new StaffContact(user.userId(), user.email()));
                }
            }
        } while (users.hasNext());
        return new ArrayList<>(staff.values());
    }

    /**
     * The ACTIVE staff holding {@code role} who can see {@code warehouseId} - an ALL scope, or assigned to it - through
     * {@code identity :: api}'s recipient rule (SCRUM-147); never a customer. A notice about one warehouse's work goes
     * to the people who can open it.
     */
    List<StaffContact> activeStaff(Role role, UUID warehouseId) {
        return identity.notificationRecipients(Set.of(role.name()), warehouseId);
    }
}
