package com.stockflow.common.idempotency;

import java.time.Instant;

/**
 * What was stored for one idempotency key.
 *
 * <p>A plain record, not an entity: the store is a port, and a future implementation on Redis
 * should not have to inherit JPA's shape.</p>
 *
 * @param status         where the original request got to
 * @param responseStatus HTTP status of the completed original, or 0 while in progress
 * @param responseBody   the exact bytes the original returned, replayed verbatim on a duplicate
 */
public record IdempotencyRecord(
        String key,
        String caller,
        String fingerprint,
        Status status,
        int responseStatus,
        String responseBody,
        Instant createdAt,
        Instant expiresAt
) {

    public enum Status {
        /**
         * The original request is still running.
         *
         * <p>A duplicate arriving now must not be processed and must not be answered with the
         * original's result, because there is not one yet. It gets 409 and is expected to retry —
         * which is why {@link com.stockflow.common.error.ErrorCode#IDEMPOTENT_REQUEST_IN_PROGRESS}
         * is marked retryable.</p>
         */
        IN_PROGRESS,

        /** The original finished. Its status and body are stored and replayed to duplicates. */
        COMPLETED
    }

    public boolean isReplayable() {
        return status == Status.COMPLETED;
    }

    public boolean matches(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }
}
