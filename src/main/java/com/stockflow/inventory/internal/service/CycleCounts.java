package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;

import java.util.UUID;

/**
 * Cycle counts: listed for the warehouse that has to count, and called off by the manager. The blind recounts a short
 * pick opens (docs 04c X-SHORT step 03) are the first of them; planning, counting and posting the difference grow
 * here (SCRUM-146).
 *
 * <p>Inside {@code internal}: only this module's web layer reads it.</p>
 */
public interface CycleCounts {

    /**
     * Counts, newest first. A blind count hides its expected quantity, note and adjustments from the caller unless
     * they may decide counts ({@code showExpected}). A warehouse-bound caller sees their own warehouses only.
     *
     * @param status    PLANNED, IN_PROGRESS, COMPLETED or CANCELLED, in any case; null for any
     * @param warehouse one warehouse; null for every warehouse the caller may see
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown status;
     *         {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<CycleCountView> list(String status, UUID warehouse, boolean showExpected, int page, int size);

    /**
     * The warehouse manager calls a count off (it is not needed, or the bin was checked by hand), which also lifts
     * the freeze a SHORT_PICK recount puts on the bin, SKU and lot. Cancelling a cancelled count again is a no-op.
     *
     * @param actor who cancelled; logged, since the audit columns are stamped from the security context
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code CYCLE_COUNT_NOT_OPEN} once COMPLETED
     */
    CycleCountView cancel(UUID id, String reason, String actor);
}
