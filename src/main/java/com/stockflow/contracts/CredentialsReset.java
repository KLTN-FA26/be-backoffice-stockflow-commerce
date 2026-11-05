package com.stockflow.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Published by the <b>identity</b> module when an account's password was replaced in a way that also ends its other
 * sessions: an administrator reset it, or its owner changed it and asked to sign out everywhere else (a forced change
 * always does). Whatever still holds a credential issued before, other than a sign-in session, must stop honouring
 * it: the assistant revokes the account's personal MCP tokens (ADR-0013). Identity ends the sign-in sessions itself.
 *
 * <p>Why an event and not a call: the token store belongs to the assistant, which depends on identity, not the other
 * way round. Published inside the transaction that replaces the password, it is delivered after that commits and
 * retried from the event publication registry if the listener fails; a reset that rolls back revokes nothing.</p>
 *
 * <p>Keyed by {@code (userId, at)}; revoking twice changes nothing, so a consumer that sees it twice is harmless.</p>
 *
 * @param userId the account whose password was replaced
 * @param cause  how it was replaced
 * @param at     when
 */
public record CredentialsReset(UUID userId, Cause cause, Instant at) {

    public CredentialsReset {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(at, "at");
    }

    /** How the password was replaced. */
    public enum Cause {
        /** An administrator set a new (often temporary) password: the old one may be known to someone else. */
        ADMINISTRATOR_RESET,
        /** The owner changed it and ended every other session, by choice or because the change was forced. */
        CHANGED_SIGNING_OUT_ELSEWHERE
    }
}
