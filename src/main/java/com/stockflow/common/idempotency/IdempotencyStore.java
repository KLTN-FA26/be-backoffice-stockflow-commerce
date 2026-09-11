package com.stockflow.common.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * Where idempotency records live. A port, so the filter does not know about JPA.
 *
 * <p>The one method whose contract really matters is {@link #beginIfAbsent}: it has to be
 * <b>atomic</b>. Two duplicate requests arriving at the same moment on two threads must not both
 * see "no record" and both proceed — that is precisely the double-submit this whole mechanism
 * exists to stop, and a check-then-insert in application code loses that race every time under
 * load.</p>
 */
public interface IdempotencyStore {

    /**
     * Atomically claim the key, or report who already holds it.
     *
     * @return empty if the claim succeeded and the caller should proceed; the existing record if
     *         the key was already taken
     */
    Optional<IdempotencyRecord> beginIfAbsent(String key, String caller, String fingerprint,
                                              Instant now, Instant expiresAt);

    /** Store the outcome so duplicates can be answered without re-running anything. */
    void complete(String key, String caller, int responseStatus, String responseBody, Instant now);

    /**
     * Give up the claim, so the client can retry.
     *
     * <p>Called when the original request failed with a 5xx or threw. Leaving an
     * {@code IN_PROGRESS} record behind after a crash would lock that key out until it expired —
     * the client would get 409 forever and have no way to make progress.</p>
     */
    void abandon(String key, String caller);

    /** Housekeeping: drop records past their expiry. Called by a scheduled job. */
    int deleteExpired(Instant now, int batchSize);
}
