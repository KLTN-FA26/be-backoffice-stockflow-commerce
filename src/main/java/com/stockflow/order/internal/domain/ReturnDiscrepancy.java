package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What came back on a return's receipt that the approval did not say ({@code ordering.return_request_discrepancy},
 * append-only; team plan 4D-3): fewer ({@link Kind#SHORT}) or more ({@link Kind#EXCESS}) units of a line than approved,
 * or cartons of a SKU the return does not claim ({@link Kind#UNEXPECTED_ITEM}). Recorded when the return receipt is
 * confirmed, for Sales to follow up with the customer outside the system (kltn-docs 03 edge cases); the units refunded
 * are Sales' proposal, never a discrepancy's. {@link Kind#WRONG_ITEM} is historical: the warehouse counted loose units
 * of another SKU in before 4D-3, a return receipt takes only cartons of the order.
 *
 * @param lineId null only for an unexpected item
 */
public record ReturnDiscrepancy(UUID id, UUID lineId, Kind kind, String sku, int quantity, String note,
                                UUID recordedBy, Instant recordedAt) {

    public enum Kind { SHORT, EXCESS, WRONG_ITEM, UNEXPECTED_ITEM }

    public ReturnDiscrepancy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(recordedBy, "recordedBy");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A discrepancy counts at least one unit");
        }
        if ((kind == Kind.UNEXPECTED_ITEM) != (lineId == null)) {
            throw new IllegalArgumentException("Only an unexpected item belongs to no line");
        }
    }
}
