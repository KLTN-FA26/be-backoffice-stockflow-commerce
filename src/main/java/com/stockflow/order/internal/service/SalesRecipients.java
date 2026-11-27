package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.security.Roles;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffUser;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about a contract or an order on Sales' side (WF-02 FLOW-4): the salesperson responsible for it - who sent
 * its contract, or for none recorded who recorded it signed (Q-11) - while that account is ACTIVE. When it is gone,
 * locked or unknown, every active Sales account hears instead, marked as a hand-over, the rule docs 00 §6 already gives
 * for a quote ({@code QuoteNotificationListener}). It only chooses who is told; who may act is unchanged.
 */
@Component
class SalesRecipients {

    private final IdentityService identity;

    SalesRecipients(IdentityService identity) {
        this.identity = identity;
    }

    /** Who is told, and whether it is a hand-over from an account that is no longer active. */
    record Recipients(List<UUID> userIds, boolean handover) {

        boolean nobody() {
            return userIds.isEmpty();
        }
    }

    /** A chooser for one run: each account, and the Sales team, is read once. */
    Chooser chooser() {
        return new Chooser();
    }

    final class Chooser {

        private final Map<UUID, Boolean> active = new HashMap<>();
        private List<UUID> team;

        Recipients of(UUID salesUserId) {
            if (salesUserId != null && active.computeIfAbsent(salesUserId, SalesRecipients.this::isActive)) {
                return new Recipients(List.of(salesUserId), false);
            }
            if (team == null) {
                team = identity.notificationRecipients(Set.of(Roles.SALES_STAFF), null).stream()
                        .map(StaffContact::userId).distinct().toList();
            }
            return new Recipients(team, true);
        }
    }

    private boolean isActive(UUID userId) {
        try {
            StaffUser user = identity.user(userId);
            return user != null && "ACTIVE".equals(user.status());
        } catch (BusinessException gone) {
            return false;
        }
    }
}
