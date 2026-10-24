package com.stockflow.inventory.consumption;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A picker at a pick line's bin found {@code quantity} of the units the line's allocation holds damaged (SCRUM-277).
 *
 * @param requestId          the report's idempotency key: the same key again answers with the first report
 * @param reservationId      the allocation behind the pick line
 * @param pickLineId         the pick line, named on the report
 * @param orderId            the order the allocation belongs to; a hold of another order is refused
 * @param photos             at least one, from {@link PickDamageReporting#storePhotos} in this transaction
 * @param damageLocationCode the quarantine area the picker takes the units to; null lets inventory choose one of the
 *                           warehouse's (the first usable one, by code, that can take these goods)
 */
public record ReportPickDamageCommand(UUID requestId, UUID reservationId, UUID pickLineId, UUID orderId,
                                      int quantity, StockDamageReason reason, String note, List<DamagePhoto> photos,
                                      String damageLocationCode, UUID actorId) {

    public ReportPickDamageCommand {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(pickLineId, "pickLineId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(actorId, "actorId");
        photos = photos == null ? List.of() : List.copyOf(photos);
    }
}
