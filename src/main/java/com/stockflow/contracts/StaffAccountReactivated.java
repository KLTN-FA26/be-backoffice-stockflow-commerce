package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>identity</b> when a locked or disabled staff account became ACTIVE again (unlock, enable). Nothing
 * returns to it by itself (sales-owner plan BR-S10): customers moved away stay where a manager put them. Consumers may
 * tell the managers, or count the account as an agent again. Delivered after commit; key {@code (userId, at)}.
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
