package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * Space a putaway trip holds at a bin for a carton on its cart (kltn-docs 05 BR-16). How much it is - the carton's
 * volume and full weight - is the inventory module's to say; this names the carton, its task and the bin.
 */
public record PutawayHold(UUID tripId, UUID putawayTaskId, UUID stockPackageId, UUID locationId) {
}
