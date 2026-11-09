package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * Deducts the carton a pick line takes, and books it back whole when the order is cancelled afterwards
 * (kltn-docs 07 Output "giảm tồn tại vị trí nguồn", BR-05; SCRUM-275; team plan 4C-4). Custom-printed goods pass through here like
 * any other: they are printed outside the system and received into stock before they are picked, and packing deducts
 * nothing.
 *
 * <p>Both methods join the caller's transaction and are idempotent per pick line: the ledger row a
 * line wrote ({@code inventory.stock_movement}, reference {@code PICK_LINE}) is how a repeat is
 * recognised, and a unique index on it is the last line of defence. Both lock the stock row they
 * change, so a caller that also locks its own pick task must take that lock first — fulfillment
 * always locks the pick, then calls in here, never the other way round.</p>
 */
public interface PickConsumption {

    /**
     * Turns the hold behind a pick line into a real deduction: {@code on_hand} falls by the hold's
     * quantity, the hold becomes CONSUMED, one {@code PICK} ledger row names the pick line and
     * {@code StockDeducted} is published. Called again for the same pick line, it changes nothing and
     * answers {@code alreadyConsumed = true}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND};
     *         {@code RESERVATION_NOT_ACTIVE} when the hold was released, expired or consumed by something
     *         else; {@code INVENTORY_POLICY_STOCK_CONFLICT} when the stock is blocked or its lot expired;
     *         {@code OUT_OF_DATA_SCOPE} for a hold outside the caller's warehouses
     */
    ConsumedPick consumeForPick(UUID reservationId, UUID pickLineId, UUID actorId);

    /**
     * Books the carton a cancelled order's pick line took back onto a bin, whole (kltn-docs 07 §4.5 step 14, team plan
     * 4C-4): the carton goes CONSUMED to IN_STOCK, sealed, at the bin, keeping its LPN; its stock row - the layer the
     * pick emptied, same SKU, lot and receipt - follows it there and holds the carton's count again, AVAILABLE; one
     * {@code PUT_BACK} ledger row names the pick line and the carton. Called again for the same pick line, it changes
     * nothing. Whether the carton fits the bin (BR-12) is not checked here (team plan 5-4).
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} when the line's
     *         hold is unknown; {@code LOCATION_NOT_FOUND} / {@code LOCATION_NOT_USABLE} for the bin;
     *         {@code STOCK_PACKAGE_TAKEN_WHOLE} when the hold is not that whole carton, emptied by this pick (loose
     *         stock, part of a carton, units of it found damaged at packing); {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         when the carton is not CONSUMED; {@code STOCK_STATUS_MISMATCH} when its row is not AVAILABLE stock;
     *         {@code OUT_OF_DATA_SCOPE}
     */
    void putBack(PutBackCommand command);

    /**
     * A picker came up short at an allocation's bin (docs 04c X-SHORT step 03): the books and the shelf disagree, so the
     * bin is counted again. Opens a blind {@code SHORT_PICK} cycle count of the allocation's bin, SKU and lot (or its
     * carton) for today, or answers with the count already holding that slot: one still open, or one submitted whose
     * variance waits for approval, whatever its trigger. Joins the caller's transaction; changes no stock.
     *
     * @param found the units the picker found, for the count's note
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} for an allocation not on a
     *         stock item; {@code OUT_OF_DATA_SCOPE} outside the caller's warehouses
     */
    ShortPickRecount requestRecount(UUID reservationId, UUID pickLineId, int found);
}
