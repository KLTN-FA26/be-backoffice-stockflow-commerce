package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.Recounts;

import java.util.UUID;

/**
 * Cycle counts as far as they exist yet: the blind recounts a short pick opens (docs 04c X-SHORT step 03), listed for
 * the warehouse that has to count. Counting and posting the difference are the cycle-count story's.
 *
 * <p>Inside {@code internal}: only this module's web layer reads it.</p>
 */
public interface CycleCounts {

    /**
     * Recounts, newest first. A blind count hides its expected quantity from the caller unless they may decide counts
     * ({@code showExpected}). A warehouse-bound caller sees their own warehouses only.
     *
     * @param status    PLANNED, IN_PROGRESS, COMPLETED or CANCELLED; null for any
     * @param warehouse one warehouse; null for every warehouse the caller may see
     * @throws com.stockflow.common.error.BusinessException {@code UNSUPPORTED_PARAMETER} for an unknown status;
     *         {@code OUT_OF_DATA_SCOPE}
     */
    PageResponse<Recounts.Recount> list(String status, UUID warehouse, boolean showExpected, int page, int size);

    /**
     * The warehouse manager calls a recount off (it is not needed, or the bin was checked by hand), which also lifts
     * the freeze a SHORT_PICK recount puts on the bin, SKU and lot. Cancelling a cancelled count again is a no-op.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code OUT_OF_DATA_SCOPE};
     *         {@code CYCLE_COUNT_NOT_OPEN} once COMPLETED
     */
    Recounts.Recount cancel(UUID id, String reason, String actor);
}
