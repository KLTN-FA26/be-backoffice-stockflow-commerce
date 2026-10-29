package com.stockflow.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One administrator action on an account's status, with why (SCRUM-474 LOCK-REASON).
 *
 * @param action        what was done
 * @param fromStatus    the account's status before ({@code ACTIVE}, {@code LOCKED}, {@code DISABLED})
 * @param toStatus      and after
 * @param reason        free text, 5 to 500 characters, as the administrator typed it (trimmed)
 * @param actorId       the administrator's account; null when no caller existed
 * @param actorUsername the administrator's sign-in name at that moment
 */
public record UserStatusChangeEntry(UUID id, UserStatusChange action, String fromStatus, String toStatus,
                                    String reason, UUID actorId, String actorUsername, Instant changedAt) {
}
