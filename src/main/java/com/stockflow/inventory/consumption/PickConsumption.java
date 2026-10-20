package com.stockflow.inventory.consumption;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Deducts the stock a pick line takes, and books it back when the order is cancelled afterwards
 * (kltn-docs 07 Output "giảm tồn tại vị trí nguồn", BR-05; SCRUM-275) - and the custom-printed goods a
 * completed package takes, which are never picked (decision 2026-10-16).
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
     * Custom-printed goods leave stock when their package is complete (decision 2026-10-16; docs 04b §15-18, 04c
     * F-FUL). Each of {@code reservationIds} - the order's live print-line holds, its goods waiting in packing -
     * is consumed as a pick line's hold is: {@code on_hand} falls by the hold, the hold becomes CONSUMED, one
     * {@code PICK} ledger row per hold names the package ({@code PACKAGE}) and {@code StockDeducted} is
     * published. A print line is never picked, so this is its one deduction; dispatch deducts nothing.
     *
     * <p>Joins the caller's transaction, which also records the package complete: a failed completion leaves the
     * holds and the goods as they were. Idempotent per package: once a ledger row names it, a call for it changes
     * nothing and answers every hold {@code alreadyConsumed = true}. Rows are locked in stock-row id order.</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND};
     *         {@code RESERVATION_NOT_ACTIVE} for a hold closed, or not {@code orderId}'s;
     *         {@code PRODUCTION_HOLD_NOT_PICKABLE} for goods still in production's hands;
     *         {@code OUT_OF_DATA_SCOPE} for a hold outside the caller's warehouses
     */
    List<ConsumedPick> consumeForPack(UUID packId, UUID orderId, Collection<UUID> reservationIds, UUID actorId);

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
}
