package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>identity</b> when a locked or disabled staff account became ACTIVE again (unlock, enable), or an
 * ACTIVE account was given the Sales Staff role (again, after a revoke: review finding C3). Nothing returns to it by
 * itself (sales-owner plan BR-S10): customers moved away stay where a manager put them. The Sales Managers are told,
 * so that giving customers back is a decision someone makes. Delivered after commit; key {@code (userId, at)}.
 *
 * @param userId   the account that works again
 * @param byUserId the administrator, null when no signed-in user did it
 * @param at       when
 */
public record StaffAccountReactivated(UUID userId, UUID byUserId, Instant at) {

    public StaffAccountReactivated {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(at, "at");
    }
}
