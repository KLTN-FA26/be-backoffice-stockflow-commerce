package com.stockflow.common.audit.persistence;

import java.time.Instant;

/**
 * Retention purge, exposed as an interface so the scheduled job in {@code shared.audit} does not
 * have to reach into this package's implementation classes.
 */
public interface AuditPurger {

    /** @return how many rows were deleted; a full batch means there is more to do */
    int purgeBatch(Instant routineCutoff, Instant securityCutoff, int batchSize);
}
