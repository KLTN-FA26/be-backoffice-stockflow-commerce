package com.stockflow.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A bin a putaway may choose, with the limits the warehouse map holds for it.
 *
 * @param size        the bin's inside, which a carton must fit within (kltn-docs 06 BR-01) and whose volume is the
 *                    bin's capacity (BR-19)
 * @param maxWeightKg the heaviest load the bin takes; {@code null} when the map sets none
 */
public record PutawayCandidate(UUID locationId, String locationCode, BinSize size, BigDecimal maxWeightKg) {

    /** Computed from {@link #size}, never stored; null for a bin on a level without a clear height. */
    public BigDecimal capacityVolumeM3() {
        return size.volumeM3();
    }
}
