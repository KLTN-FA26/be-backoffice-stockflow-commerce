package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * The recount a short pick asks for (docs 04c X-SHORT step 03): a {@code SHORT_PICK} cycle count of the bin, SKU and
 * lot the picker came up short at.
 *
 * @param created false when an open recount of that bin, SKU and lot already existed and was reused
 */
public record ShortPickRecount(UUID cycleCountId, String countNumber, boolean created) {
}
