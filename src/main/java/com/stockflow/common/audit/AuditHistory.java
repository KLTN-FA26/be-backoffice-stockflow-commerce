package com.stockflow.common.audit;

import com.stockflow.common.api.PageResponse;

/**
 * The read side of the audit trail — "what happened to this record, in order".
 *
 * <p>Deliberately separate from {@link AuditTrail}: that port is write-only by design (see its own
 * javadoc), and a query method on it would mean every caller of the write path — the aspect —
 * carries a dependency it never uses. Any module may call this directly; it is part of the shared
 * base layer, not itself bound by module {@code allowedDependencies}.</p>
 *
 * <p>SCRUM-86 (WBS 3.1.8.2) is the first caller: {@code GET /products/{id}/versions}. Reuses
 * {@link AuditEntry} as the wire shape rather than inventing a parallel "version" record — an
 * audit entry already says who did what, when, and whether it succeeded, which is what "version
 * history" means anywhere else in this codebase (approve/reject/discontinue are already recorded
 * this way; this just makes the log queryable instead of write-only).</p>
 */
public interface AuditHistory {

    /**
     * Every entry for one resource, newest first.
     *
     * @param resourceType matches {@link Auditable#resourceType()} as recorded, e.g. {@code
     *                      "product"}
     * @param resourceId    matches {@link Auditable#resourceId()} as recorded
     */
    PageResponse<AuditEntry> forResource(String resourceType, String resourceId, int page, int size);
}
