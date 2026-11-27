package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by <b>identity</b> when an ACTIVE staff account was locked or disabled by an administrator (sales-owner plan
 * BR-S08), or lost the Sales Staff role while ACTIVE ({@link #SALES_ROLE_REVOKED}, review finding C3). Either way the
 * account no longer works as a salesperson, so what it was responsible for must find someone else: {@code customer}
 * marks the customers it owned as needing an owner and puts its open leads back in the managers' queue, {@code chat}
 * puts its open conversations back in the queue (unless the account still answers customers), and the managers are
 * told once. Identity itself cancels the covers in which the account was the stand-in, in the same transaction.
 *
 * <p>Not published for the automatic lock after wrong passwords (the account stays ACTIVE), nor when an account that
 * was already locked is disabled: it had stopped working already. Published inside the transaction that changes the
 * status, delivered after it commits and retried from the event publication registry; a consumer must tolerate seeing
 * it twice (key: {@code userId, at}).</p>
 *
 * @param userId   the account that stopped working
 * @param status   {@code LOCKED} or {@code DISABLED}, the account's new status; or {@link #SALES_ROLE_REVOKED}, the
 *                 account still ACTIVE
 * @param byUserId the administrator, null when no signed-in user did it
 * @param at       when
 */
public record StaffAccountDeactivated(UUID userId, String status, UUID byUserId, Instant at) {

    /** {@link #status()} of an ACTIVE account that lost the Sales Staff role: it signs in, but is no salesperson. */
    public static final String SALES_ROLE_REVOKED = "NO_LONGER_SALES";

    public StaffAccountDeactivated {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
    }
}
