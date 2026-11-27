package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>identity</b> when an ACTIVE staff account was locked or disabled by an administrator (sales-owner plan
 * BR-S08). The account can no longer sign in, so what it was responsible for must find someone else: {@code customer}
 * marks the customers it owned as needing an owner, {@code chat} puts its open conversations back in the queue.
 * Identity itself cancels the covers in which the account was the stand-in, in the same transaction.
 *
 * <p>Not published for the automatic lock after wrong passwords (the account stays ACTIVE), nor when an account that
 * was already locked is disabled: it had stopped working already. Published inside the transaction that changes the
 * status, delivered after it commits and retried from the event publication registry; a consumer must tolerate seeing
 * it twice (key: {@code userId, at}).</p>
 *
 * @param userId   the account that stopped working
 * @param status   {@code LOCKED} or {@code DISABLED}, the account's new status
 * @param byUserId the administrator, null when no signed-in user did it
 * @param at       when
 */
public record StaffAccountDeactivated(UUID userId, String status, UUID byUserId, Instant at) {

    public StaffAccountDeactivated {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
    }
}
