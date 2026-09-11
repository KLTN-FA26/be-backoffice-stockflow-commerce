package com.stockflow.common.idempotency.persistence;

import com.stockflow.common.idempotency.IdempotencyRecord;
import com.stockflow.common.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Database-backed {@link IdempotencyStore}.
 *
 * <p>Postgres rather than Redis, deliberately. The record has to survive a Redis eviction or
 * restart — losing it means a retried payment is processed twice, which is the one outcome this
 * whole mechanism exists to prevent. A cache that is allowed to forget is the wrong substrate for
 * something whose only job is to remember.</p>
 *
 * <p>All transactional work is delegated to {@link IdempotencyTransactions}; see its javadoc for
 * why that separation is not optional.</p>
 */
@Component
class JpaIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JpaIdempotencyStore.class);

    private final IdempotencyTransactions transactions;

    JpaIdempotencyStore(IdempotencyTransactions transactions) {
        this.transactions = transactions;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Insert first, ask questions second. Checking for an existing row and then inserting loses
     * the race between two simultaneous duplicates — both read "absent", both insert, one blows up
     * at commit somewhere the filter cannot handle it. Letting the unique constraint arbitrate is
     * the only version that is correct under concurrency.</p>
     *
     * <p>The retry exists for one specific case: the key was claimed by a request that has since
     * expired. That row is removed and the claim attempted once more. Bounded to a single retry —
     * a second conflict means another live request genuinely holds the key, and looping would just
     * hold the servlet thread.</p>
     */
    @Override
    public Optional<IdempotencyRecord> beginIfAbsent(String key, String caller, String fingerprint,
                                                     Instant now, Instant expiresAt) {
        try {
            transactions.insertClaim(key, caller, fingerprint, now, expiresAt);
            return Optional.empty();
        } catch (DataIntegrityViolationException firstConflict) {
            Optional<IdempotencyRecord> existing =
                    transactions.loadUnlessExpired(key, caller, now);
            if (existing.isPresent()) {
                return existing;
            }
            // The row was expired and has just been deleted; the key is free again.
            try {
                transactions.insertClaim(key, caller, fingerprint, now, expiresAt);
                return Optional.empty();
            } catch (DataIntegrityViolationException secondConflict) {
                // Somebody else claimed it in the microseconds between the delete and this insert.
                // Their claim is valid; report it rather than retrying indefinitely.
                log.debug("Idempotency key {} was re-claimed concurrently", key);
                return transactions.load(key, caller);
            }
        }
    }

    @Override
    public void complete(String key, String caller, int responseStatus, String responseBody,
                         Instant now) {
        transactions.complete(key, caller, responseStatus, responseBody, now);
    }

    @Override
    public void abandon(String key, String caller) {
        transactions.abandon(key, caller);
    }

    @Override
    public int deleteExpired(Instant now, int batchSize) {
        return transactions.deleteExpired(now, batchSize);
    }
}
