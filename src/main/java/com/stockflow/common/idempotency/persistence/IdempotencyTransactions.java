package com.stockflow.common.idempotency.persistence;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.idempotency.IdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The transactional half of the idempotency store, in its own bean.
 *
 * <h2>Why a separate class rather than methods on the store</h2>
 *
 * <p>Two reasons, and both are the kind of thing that produces a bug nobody can explain:</p>
 *
 * <p><b>1. Self-invocation defeats Spring's proxy.</b> {@code JpaIdempotencyStore} needs to call a
 * {@code REQUIRES_NEW} method and <i>catch</i> the constraint violation it may throw. Calling
 * {@code this.insertNew(...)} would bypass the transactional proxy entirely, so there would be no
 * new transaction — the insert would join the caller's, and the catch would be inside the
 * transaction it was supposed to isolate. Crossing a bean boundary is what makes the proxy
 * apply.</p>
 *
 * <p><b>2. A failed insert poisons its transaction.</b> Once a constraint violation is thrown, that
 * transaction is marked rollback-only and every subsequent statement in it fails. So the catch must
 * be <i>outside</i> the transaction that did the insert, and the follow-up read must run in a
 * different one. That is exactly what {@code REQUIRES_NEW} on each method here provides.</p>

 * <h2>Why the methods are public on a package-private class</h2>
 *
 * <p>Spring advises {@code @Transactional} through a proxy. Spring 6 does accept package-private
 * methods on a CGLIB proxy, but only on a CGLIB proxy — flip {@code spring.aop.proxy-target-class}
 * to false and every {@code REQUIRES_NEW} boundary in this class silently disappears, taking the
 * idempotency guarantee with it and reporting nothing. {@code public} on a class that is itself
 * package-private gives up no encapsulation at all (nothing outside this package can name the type)
 * and removes the dependency on that subtlety entirely.</p>
 */
@Component
class IdempotencyTransactions {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTransactions.class);

    private final IdempotencyJpaRepository repository;

    IdempotencyTransactions(IdempotencyJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * Insert the claim, committing immediately.
     *
     * <p>{@code saveAndFlush} rather than {@code save}: the constraint has to be checked <b>now</b>,
     * inside this call, so the caller can catch the violation. With a deferred flush the exception
     * would surface at commit time, somewhere the caller has no handler.</p>
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the key is already claimed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertClaim(String key, String caller, String fingerprint, Instant now, Instant expiresAt) {
        repository.saveAndFlush(new IdempotencyRecordEntity(
                Identifiers.newId(), key, caller, fingerprint, now, expiresAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> load(String key, String caller) {
        return repository.findByKeyAndCaller(key, caller).map(IdempotencyRecordEntity::toRecord);
    }

    /**
     * Load, and drop the row if it is no longer usable.
     *
     * <p>Two ways a row stops being usable, and both must be handled here or the key stays locked:
     * the retention window has passed, or it is an {@code IN_PROGRESS} claim whose owner died
     * without running its cleanup (see {@link IdempotencyRecordEntity#CLAIM_TIMEOUT}).</p>
     *
     * @return the record if it is still live, or empty if it was stale and has been removed —
     *         in which case the caller may claim the key afresh
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> loadUnlessExpired(String key, String caller, Instant now) {
        Optional<IdempotencyRecordEntity> found = repository.findByKeyAndCaller(key, caller);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecordEntity entity = found.get();
        if (entity.isExpiredAt(now)) {
            repository.delete(entity);
            return Optional.empty();
        }
        if (entity.isAbandonedClaimAt(now)) {
            log.warn("Idempotency key {} held an IN_PROGRESS claim older than {} - the request that "
                            + "made it did not finish. Releasing the key.",
                    key, IdempotencyRecordEntity.CLAIM_TIMEOUT);
            repository.delete(entity);
            return Optional.empty();
        }
        return Optional.of(entity.toRecord());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, String caller, int responseStatus, String responseBody, Instant now) {
        repository.findByKeyAndCaller(key, caller)
                .ifPresent(entity -> entity.complete(responseStatus, responseBody, now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(String key, String caller) {
        repository.deleteByKeyAndCaller(key, caller);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(Instant now, int batchSize) {
        return repository.deleteExpired(now, batchSize);
    }
}
