package com.stockflow.inventory.consumption;

import java.util.UUID;

/**
 * Deducts the carton a pick line takes, and books it back whole when the order is cancelled afterwards
 * (kltn-docs 07 Output "giảm tồn tại vị trí nguồn", BR-05; SCRUM-275; team plan 4C-4). Custom-printed goods pass through here like
 * any other: they are printed outside the system and received into stock before they are picked, and packing deducts
 * nothing.
 *
 * <p>A task whose destination is the staging area (team plan 4C-5, kltn-docs 07 §1, 20 §4.9) takes three steps instead
 * of one: {@link #stage} moves the carton to the staging area and leaves it held for the order; {@link #consumeStaged}
 * deducts it there when the whole task moves to dispatch; or, the order cancelled, {@link #unstage} moves it back to a
 * bin, nothing having been deducted.</p>
 *
 * <p>Every method joins the caller's transaction and is idempotent per pick line: the ledger row a
 * line wrote ({@code inventory.stock_movement}, reference {@code PICK_LINE}) is how a repeat is
 * recognised, and a unique index on it is the last line of defence. Each locks the stock row it
 * changes, so a caller that also locks its own pick task must take that lock first — fulfillment
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
     *         stock, part of a carton); {@code INVALID_STOCK_PACKAGE_TRANSITION}
     *         when the carton is not CONSUMED; {@code STOCK_STATUS_MISMATCH} when its row is not AVAILABLE stock;
     *         {@code OUT_OF_DATA_SCOPE}
     */
    void putBack(PutBackCommand command);

    /**
     * Books a carton the check station removed as damaged back into stock, whole, in a QUARANTINE area of its own
     * warehouse (kltn-docs 08 §4.2 step 4: "cất nguyên thùng vào khu QUARANTINE"; 20 §4.8; team plan 4C-6). As
     * {@link #putBack}, the carton goes CONSUMED to IN_STOCK keeping its LPN and count and its row follows it with the
     * carton's count again - but as QUARANTINE stock: never sellable, out of ATP and allocation until the warehouse
     * manager releases it to a bin or writes it off (20 §4.8, team plan 4D-1). One {@code PUT_BACK} ledger row names the
     * pick line and the carton. Called again for the same pick line, it changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException as {@link #putBack}; {@code LOCATION_AREA_MISMATCH} when the
     *         target is not a QUARANTINE area; {@code MOVE_ACROSS_WAREHOUSES} for one of another warehouse
     */
    void putBackToQuarantine(PutBackCommand command);

    /**
     * Moves the carton a pick line takes from its bin to a staging area, whole (kltn-docs 07 §4.2 step 5 "STAGING", 20
     * §4.9 step 1): the carton and its stock row stand there now, the allocation still HELD on it, so the order keeps it
     * and nobody else can promise it (20 BR-20); one {@code MOVE} ledger row names the pick line and the carton. Nothing
     * is deducted. Called again for the same pick line, it changes nothing and answers {@code alreadyStaged = true}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND}; {@code RESERVATION_NOT_ACTIVE}
     *         when the allocation was released or consumed; {@code STOCK_PACKAGE_TAKEN_WHOLE} unless the allocation is a
     *         whole carton in stock; {@code LOCATION_NOT_FOUND} / {@code LOCATION_NOT_USABLE} /
     *         {@code STAGING_AREA_INVALID} / {@code MOVE_ACROSS_WAREHOUSES} for the staging area;
     *         {@code STOCK_STATUS_MISMATCH} when the stock is not AVAILABLE; {@code OUT_OF_DATA_SCOPE}
     */
    StagedPick stage(StageCommand command);

    /**
     * Deducts a carton {@link #stage} put in a staging area, where it stands (kltn-docs 07 §4.3 step 8, 20 §4.9 step 3):
     * as {@link #consumeForPick} - the allocation CONSUMED, {@code on_hand} falls, the carton CONSUMED, one {@code PICK}
     * ledger row from the staging area names the pick line. Called again for the same pick line, it changes nothing and
     * answers {@code alreadyConsumed = true}.
     *
     * @throws com.stockflow.common.error.BusinessException as {@link #consumeForPick}
     * @throws IllegalStateException when this pick line never staged the carton, or it is no longer in a staging area
     */
    ConsumedPick consumeStaged(UUID reservationId, UUID pickLineId, UUID actorId);

    /**
     * {@link #consumeStaged(UUID, UUID, UUID)} for every staged line of a task at once, as the move to dispatch needs it
     * (07 §4.3 step 8: the whole task, once). One set of locks for all of them, in the order every multi-SKU writer
     * keeps - the SKU advisory locks sorted, then the cartons, then the rows by id - so the move cannot deadlock with a
     * checkout or a release reserving the same SKUs in the other order. Each carton must still be in stock where its
     * line staged it: a carton gone from the staging area is a shortage to report on the line, never a shipment.
     *
     * @return what each line deducted, in the order given
     * @throws com.stockflow.common.error.BusinessException as {@link #consumeForPick}; {@code CONFLICT} for a carton no
     *         longer in stock in the staging area its line moved it to
     */
    java.util.List<ConsumedPick> consumeStaged(java.util.List<StagedLine> lines, UUID actorId);

    /**
     * Moves a carton {@link #stage} put in a staging area back onto a bin, whole, after its order was cancelled (kltn-docs
     * 07 §4.5 steps 13-14, 20 §4.9 step 3): nothing was deducted, so this is a {@code MOVE} from the staging area naming
     * the pick line and the carton, and the carton is IN_STOCK at the bin as it was - available to promise again, its
     * allocation released by the cancellation. Called again for the same pick line, it changes nothing. Whether the
     * carton fits the bin (07 BR-12) is not checked here (team plan 5-4).
     *
     * @throws com.stockflow.common.error.BusinessException {@code RESERVATION_NOT_FOUND}; {@code CONFLICT} while the
     *         allocation is still held for its order; {@code LOCATION_NOT_FOUND} / {@code LOCATION_NOT_USABLE} /
     *         {@code MOVE_ACROSS_WAREHOUSES} / {@code STAGING_MOVE_NOT_ALLOWED} for the bin; {@code OUT_OF_DATA_SCOPE}
     * @throws IllegalStateException when this pick line never staged the carton
     */
    void unstage(PutBackCommand command);

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
