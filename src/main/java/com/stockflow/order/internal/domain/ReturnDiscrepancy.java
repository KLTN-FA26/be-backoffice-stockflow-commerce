package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a returned parcel held that the approval did not say ({@code ordering.return_request_discrepancy},
 * append-only): fewer ({@link Kind#SHORT}) or more ({@link Kind#EXCESS}) units of a line than approved, another SKU
 * than the line's ({@link Kind#WRONG_ITEM}), or something that belongs to no line ({@link Kind#UNEXPECTED_ITEM}, its
 * SKU as the warehouse read it - it may not even be ours). Recorded at receipt for Sales to follow up; the units
 * credited are decided at inspection, never from a discrepancy alone.
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
