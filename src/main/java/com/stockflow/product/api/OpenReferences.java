package com.stockflow.product.api;

import java.util.List;

/**
 * The open documents that still refer to a product's SKUs, a bounded sample (SCRUM-461).
 *
 * @param references at most {@link #LIMIT}, most recent first within each kind
 * @param truncated  there are more than listed
 */
public record OpenReferences(List<SkuReference> references, boolean truncated) {

    /** Enough for a person to see what is open; the full list is a report, not a warning. */
    public static final int LIMIT = 20;

    public OpenReferences {
        references = references == null ? List.of() : List.copyOf(references);
    }
}
