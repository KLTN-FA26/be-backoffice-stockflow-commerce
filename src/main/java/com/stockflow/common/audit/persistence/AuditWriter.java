package com.stockflow.common.audit.persistence;

import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.id.Identifiers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The two ways an audit entry gets written, separated because they need different propagation.
 *
 * <h2>Why success and failure are not written the same way</h2>
 *
 * <p><b>Success joins the caller's transaction.</b> The entry commits with the change it describes,
 * so a business operation that is later rolled back leaves no audit row claiming it happened. An
 * audit trail that records things that did not occur is worse than no trail at all.</p>
 *
 * <p><b>Failure needs its own transaction.</b> When the audited method throws, the caller's
 * transaction is on its way to being rolled back — an entry written into it would vanish with it,
 * and exactly the entries an investigator wants (the refused approvals, the failed permission
 * changes) would be the ones that never survive. {@code REQUIRES_NEW} commits the failure entry
 * independently of the operation's fate.</p>
 *
 * <p>Separate bean so Spring's proxy applies; a self-invocation would silently ignore the
 * propagation and put both paths in the caller's transaction.</p>

 * <h2>Why the methods are public on a package-private class</h2>
 *
 * <p>The transaction boundaries here only exist if Spring's proxy can intercept the call. Spring 6
 * accepts package-private methods on a CGLIB proxy, but that is a CGLIB-only behaviour; {@code
 * public} on a type nothing outside this package can name costs no encapsulation and does not
 * depend on it.</p>
 */
@Component
class AuditWriter {

    private final AuditLogJpaRepository repository;

    AuditWriter(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes one entry in a transaction of its own.
     *
     * <h2>Why never {@code REQUIRED}</h2>
     *
     * <p>Joining the caller's transaction sounds stronger — the entry would commit atomically with
     * the change it describes — and it breaks the one guarantee {@code AuditTrail} makes: that the
     * audit trail can never fail the operation it observes.</p>
     *
     * <p>The failure is not obvious, because {@code JpaAuditTrail} catches the exception. Catching
     * it does not undo the damage. With {@code REQUIRED} and an outer transaction present, a failed
     * {@code save} has already marked that transaction <b>rollback-only</b>. {@code JpaAuditTrail}
     * logs and returns, the business method returns normally, and the outer commit then throws
     * {@code UnexpectedRollbackException} — discarding work that had nothing wrong with it. An
     * audit table that is briefly unreachable would start failing business operations, which is
     * precisely the "the monitor takes down the system it monitors" outcome the contract forbids.
     * A {@code catch} cannot fix that; only a separate transaction can.</p>
     *
     * <h2>What is given up, and why it costs nothing here</h2>
     *
     * <p>An independent write means an operation that rolls back <i>after</i> the entry is written
     * could leave an entry describing something that did not happen. In this codebase it cannot:
     * {@code AuditAspect} is the only caller, it sits outside the transaction advisor, and it
     * records only once {@code proceed()} has returned — by which point the audited method's
     * transaction has already committed. The entry describes a change that is durable.</p>
     *
     * <p>Failure entries need the same independence for the opposite reason: the refused approvals
     * and rejected permission changes are exactly what an investigator comes looking for, and
     * joining the caller's transaction would roll them back along with the operation that failed.
     * One propagation setting is correct for both cases, so there is only one method.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditEntry entry) {
        repository.save(new AuditLogEntity(Identifiers.newId(), entry));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purge(Instant routineCutoff, Instant securityCutoff, int batchSize) {
        return repository.purge(routineCutoff, securityCutoff, batchSize);
    }
}
