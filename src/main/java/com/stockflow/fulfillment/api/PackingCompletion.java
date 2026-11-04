package com.stockflow.fulfillment.api;

import java.util.UUID;

/**
 * How a packer completes packing (kltn-docs 08 step 7-9): every sealed package not shipped yet becomes one shipment.
 *
 * @param requestId the caller's id for this completion: the same id again answers with the shipment it made; null to
 *                  let a completed packing answer as it is
 * @param partial   ship what is sealed although the order wants more (split shipments, G4-Q3; a mixed order's ready
 *                  stock lines first, decision 2026-10-21; 08 edge "partial ship"); the rest is packed later
 * @param note      why only part of the order goes; required with {@code partial}
 */
public record PackingCompletion(UUID requestId, boolean partial, String note) {

    /** Everything at once, the default. */
    public static PackingCompletion whole() {
        return new PackingCompletion(null, false, null);
    }
}
