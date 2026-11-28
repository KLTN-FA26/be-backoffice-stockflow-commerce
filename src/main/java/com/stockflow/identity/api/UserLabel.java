package com.stockflow.identity.api;

import java.util.UUID;

/**
 * What a screen shows to say who did something, and whether that account still works here: the display name and
 * whether the account is ACTIVE. The back office customer list names the salesperson who last dealt with each customer
 * (owner decision 2026-11-27) and has to show one who has left as such, which {@link IdentityService#displayNames}
 * alone cannot tell it. No e-mail, roles or the reason an account was locked: user management shows those.
 *
 * @param displayName full name, or the username when none was given (as {@link IdentityService#displayNames})
 * @param active      the account's status is ACTIVE; false for a LOCKED or DISABLED one - the same test
 *                    the order module uses to decide whether a salesperson is still there to be told
 */
public record UserLabel(UUID userId, String displayName, boolean active) {
}
