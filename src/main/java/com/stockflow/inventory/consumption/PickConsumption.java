package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * Deducts the stock a pick line takes, and books it back when the order is cancelled afterwards
 * (kltn-docs 07 Output "giảm tồn tại vị trí nguồn", BR-05; SCRUM-275). Custom-printed goods are never picked and
 * do not pass through here: they leave stock when their production order completes (decision G4-Q2, 2026-10-21;
 * {@code InventoryService#consumeFinishedGoods}), and packing deducts nothing for them.
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
     * Books picked goods of a cancelled order back onto a bin as AVAILABLE stock, into the stock layer
     * they were picked from (same SKU, lot, expiry and receipt) at that bin, or a new one there; writes
     * one {@code PUT_BACK} ledger row naming the pick line. Called again for the same pick line, it
     * changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND} when the line's
     *         hold is unknown; {@code LOCATION_NOT_FOUND} / {@code LOCATION_NOT_USABLE} for the bin;
     *         {@code STOCK_STATUS_MISMATCH} when that bin holds the same layer in another status;
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
