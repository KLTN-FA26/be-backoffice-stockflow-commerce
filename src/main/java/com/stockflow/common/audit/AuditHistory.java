package com.stockflow.common.audit;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

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
 *
 * <p>The System Admin's audit log screen (kltn-docs 00 §2.5; 13 §4.2 and 06 §4.1 step 8 say changes are audited, so
 * someone must be able to read them) searches the whole trail with {@link #search} and opens a line with
 * {@link #find}.</p>
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

    /**
     * The entries matching {@code filter}, newest first (ties by id, so a page boundary never repeats or skips one).
     *
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for a page below 0 or a size
     *         outside 1 to {@link com.stockflow.common.persistence.Pages#MAX_PAGE_SIZE}
     */
    PageResponse<AuditRecord> search(AuditSearch filter, int page, int size);

    /** One entry by the id it was stored under; empty when there is none, or it was purged ({@code AuditRetention}). */
    Optional<AuditRecord> find(UUID id);
}
