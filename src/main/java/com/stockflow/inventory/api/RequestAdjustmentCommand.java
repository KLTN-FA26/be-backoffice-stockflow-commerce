package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * Ask for one whole carton to be written off, by its LPN (kltn-docs 20 BR-14, BR-21; team plan 4D-1). Nothing changes
 * until someone else approves it.
 *
 * <p>There is no quantity: stock is whole cartons only, so a write-off takes the carton, whatever it holds
 * (ADR-0011). A request that names no carton is refused {@code STOCK_NOT_IN_CARTON}.</p>
 *
 * @param lpn          the carton to write off whole
 * @param locationCode optional; when given it must be the carton's
 * @param sku          optional; when given it must be the carton's
 * @param note         required when the reason is {@link StockAdjustmentReason#OTHER}
 */
public record RequestAdjustmentCommand(
        String lpn,
        String locationCode,
        Sku sku,
        StockAdjustmentReason reason,
        String note,
        UUID requestedBy
) {

    /** A whole carton, written off by its LPN. */
    public static RequestAdjustmentCommand ofCarton(String lpn, StockAdjustmentReason reason, String note,
                                                    UUID requestedBy) {
        return new RequestAdjustmentCommand(Objects.requireNonNull(lpn, "lpn"), null, null, reason, note, requestedBy);
    }

    public RequestAdjustmentCommand {
        lpn = lpn == null || lpn.isBlank() ? null : lpn;
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
