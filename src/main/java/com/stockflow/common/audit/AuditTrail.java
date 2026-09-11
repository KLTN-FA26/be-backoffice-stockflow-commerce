package com.stockflow.common.audit;

/**
 * Where audit entries go. A port, so the aspect does not depend on JPA and a future implementation
 * can ship entries to a log pipeline instead.
 */
public interface AuditTrail {

    /**
     * Record one entry.
     *
     * <p><b>Must not throw.</b> An audit write that fails has to be logged and swallowed, never
     * propagated: failing the user's operation because the audit table was unreachable would mean
     * the audit trail can take down the system it is meant to observe. Implementations own that
     * guarantee so no caller has to wrap the call in a try/catch.</p>
     */
    void record(AuditEntry entry);
}
