package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * A carton one pick line moved to a staging area, not deducted (team plan 4C-5).
 *
 * @param fromLocationCode    the bin it came from
 * @param stagingLocationCode the staging area it stands in now
 * @param alreadyStaged       true when this pick line had staged it before and nothing changed now
 */
public record StagedPick(UUID reservationId, String lpn, String sku, int quantity, String fromLocationCode,
                         String stagingLocationCode, boolean alreadyStaged) {
}
