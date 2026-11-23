package com.stockflow.inventory.api;

import java.util.Objects;

/**
 * What a return's refund decided for one SKU (WF-05 Q-4; audit round 3, RAD-1): the units of it that came back, in
 * sealed cartons, opened cartons and loose, and the units of it refunded. The cartons of the return are settled per SKU
 * on it ({@link CartonReturns#settleOwnership}): goods are the company's as far as the refund agreed to take them back,
 * and no further.
 *
 * @param unitsBack     the units of the SKU the return's parcels brought (its lines' received quantity)
 * @param unitsRefunded the units of it refunded; 0 when it was refused or nothing usable of it came back
 */
public record RefundedSku(String sku, int unitsBack, int unitsRefunded) {

    public RefundedSku {
        Objects.requireNonNull(sku, "sku");
        if (unitsBack < 0 || unitsRefunded < 0 || unitsRefunded > unitsBack) {
            throw new IllegalArgumentException("%s: %d refunded of %d back".formatted(sku, unitsRefunded, unitsBack));
        }
    }
}
