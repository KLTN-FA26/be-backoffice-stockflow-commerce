package com.stockflow.common.audit.persistence;

import com.stockflow.common.audit.AuditEntry;
import com.stockflow.common.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Database-backed {@link AuditTrail}.
 *
 * <p>It holds the contract that {@code record} never throws. Every failure is logged at
 * {@code ERROR} with the whole entry in the message, so the information survives in the application
 * log even when the table could not take it — a degraded trail, but not a lost event.</p>
 */
@Component
class JpaAuditTrail implements AuditTrail, AuditPurger {

    private static final Logger log = LoggerFactory.getLogger(JpaAuditTrail.class);

    private final AuditWriter writer;

    JpaAuditTrail(AuditWriter writer) {
        this.writer = writer;
    }

    @Override
    public int purgeBatch(java.time.Instant routineCutoff, java.time.Instant securityCutoff,
                          int batchSize) {
        return writer.purge(routineCutoff, securityCutoff, batchSize);
    }

    @Override
    public void record(AuditEntry entry) {
        try {
            // Always independent - success entries too. See AuditWriter.write for why joining the
            // caller's transaction would let an audit failure roll back the business operation
            // even though the exception is caught right here.
            writer.write(entry);
        } catch (RuntimeException ex) {
            // Contractually must not propagate: the audit trail may not fail the operation it
            // observes. The entry is preserved in the log instead of being lost outright.
            log.error("AUDIT WRITE FAILED - entry preserved here only: actor={} action={} "
                            + "resource={}/{} outcome={} correlationId={} at={}",
                    entry.actorName(), entry.action(), entry.resourceType(), entry.resourceId(),
                    entry.outcome(), entry.correlationId(), entry.occurredAt(), ex);
        }
    }
}
