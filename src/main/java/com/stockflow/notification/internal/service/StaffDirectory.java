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
     * One account by id, if it is ACTIVE and has an e-mail address; empty when it is gone, inactive or has none. A
     * notice addressed to a person reads their address when it is delivered, so the stored event names only the id.
     */
    java.util.Optional<StaffContact> activeContact(UUID userId) {
        if (userId == null) {
            return java.util.Optional.empty();
        }
        try {
            StaffUser user = identity.user(userId);
            if (user != null && "ACTIVE".equals(user.status()) && user.email() != null && !user.email().isBlank()) {
                return java.util.Optional.of(new StaffContact(user.userId(), user.email().strip()));
            }
        } catch (com.stockflow.common.error.BusinessException gone) {
            // An account deleted since the event was published: the team takes over.
        }
        return java.util.Optional.empty();
    }

    /**
     * The ACTIVE staff holding {@code role} who can see {@code warehouseId} - an ALL scope, or assigned to it - through
     * {@code identity :: api}'s recipient rule (SCRUM-147); never a customer. A notice about one warehouse's work goes
     * to the people who can open it.
     */
    List<StaffContact> activeStaff(Role role, UUID warehouseId) {
        return identity.notificationRecipients(Set.of(role.name()), warehouseId);
    }

    /**
     * The same rule, for the ACTIVE staff who may act on it: holders of a role granting {@code permission}
     * ({@code resource:ACTION}). Who is told then follows the role matrix as it is edited.
     */
    List<StaffContact> activeHolders(String permission, UUID warehouseId) {
        return identity.notificationRecipientsHolding(permission, warehouseId);
    }
}
