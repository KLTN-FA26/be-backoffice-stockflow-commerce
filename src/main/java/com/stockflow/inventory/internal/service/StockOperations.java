package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackagesCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.StockMovement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves, adjustments and the ledger (SCRUM-424, SCRUM-145). Not on the published port as a
 * whole: other modules move stock and ask for adjustments through {@code InventoryService}; deciding
 * an adjustment and browsing the ledger are back-office actions, reached only from the controllers.
 */
public interface StockOperations {

    StockMove move(MoveStockCommand command);

    StockMove receive(ReceiveStockCommand command);

    /** See {@code InventoryService#countReceivingPackages}. */
    List<StockPackageView> countReceivingPackages(CountReceivingPackagesCommand command);

    /** See {@code InventoryService#receivingPackagesOf}. */
    Map<UUID, List<StockPackageView>> receivingPackagesOf(Collection<UUID> receiptLineIds);

    /** See {@code InventoryService#issueReserved}. */
    IssueReservedStockResult issueReserved(IssueReservedStockCommand command);

    /** See {@code InventoryService#holdReceived}. */
    com.stockflow.inventory.api.StockReservation holdReceived(com.stockflow.inventory.api.HoldReceivedStockCommand command);

    /** See {@code InventoryService#consumeHeld}. */
    StockMove consumeHeld(com.stockflow.inventory.api.ConsumeHeldStockCommand command);

    /** See {@code InventoryService#unissueHeld}. */
    IssueReservedStockResult unissueHeld(IssueReservedStockCommand command);

    /** See {@code InventoryService#finishHeld}. */
    IssueReservedStockResult finishHeld(IssueReservedStockCommand command);

    /** See {@code InventoryService#scrapCancelledPrint}. */
    Optional<StockAdjustmentSummary> scrapCancelledPrint(UUID reservationId, UUID orderId, String note,
                                                         UUID requestedBy);

    /** See {@code InventoryService#scrapCancelledPrint(ScrapCancelledPrintCommand)}. */
    List<StockAdjustmentSummary> scrapCancelledPrint(com.stockflow.inventory.api.ScrapCancelledPrintCommand command);

    /** See {@code InventoryService#consumeFinishedGoods}. */
    StockMove consumeFinishedGoods(UUID reservationId, UUID orderId, UUID productionOrderId, UUID actorId);

    /**
     * BR-PRD-10: the write-off of a cancelled order's printed goods was rejected or withdrawn; the goods are still
     * held and out of sale, and this asks again, in {@code requestedBy}'s name. A write-off still standing is
     * answered as it is.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CONFLICT} for a hold no cancellation asked to scrap,
     *         or one that is no longer printed goods waiting in packing
     */
    StockAdjustmentSummary askScrapAgain(UUID reservationId, String note, UUID requestedBy);

    /**
     * Whether {@code hold}, on the locked {@code item}, is finished custom goods of its order waiting in a PACKING
     * area - which a release does not free (BR-PRD-10).
     */
    boolean isFinishedCustomGoods(com.stockflow.inventory.internal.domain.StockItem item,
                                  com.stockflow.inventory.internal.domain.Reservation hold);

    /** See {@code InventoryService#writeOffHeld}. */
    StockAdjustmentSummary writeOffHeld(com.stockflow.inventory.api.WriteOffHeldStockCommand command);

    /** See {@code InventoryService#returnHeld}. */
    StockMove returnHeld(com.stockflow.inventory.api.ReturnHeldStockCommand command);

    StockMove reclassify(ReclassifyStockCommand command);

    /**
     * Loose INBOUND units of a receipt's lot to a bin as AVAILABLE, one ledger line per row taken from, reference
     * (PUTAWAY_TASK, taskId): a {@code MOVE} for a row taken from another place, a {@code STATUS_CHANGE} from the bin
     * to the bin for a row already standing in it (a {@code MOVE} names two different places), whole or not at all.
     * No replay lookup: two calls under one task are two parts of the putaway (spec section 6); the caller counts
     * what is already done through both kinds of ledger line. Refuses with {@code STOCK_ITEM_NOT_FOUND} when the
     * lot holds fewer than {@code units} loose INBOUND units. Returns {@code units}.
     */
    int putAwayLoose(Sku sku, UUID lotId, String toLocationCode, int units, UUID taskId, UUID actorId);

    /** See {@code InventoryService#moveStockPackages}. */
    List<StockPackageView> moveStockPackages(MoveStockPackagesCommand command);

    /** See {@code InventoryService#reclassifyStockPackages}. */
    List<StockPackageView> reclassifyStockPackages(ReclassifyStockPackagesCommand command);

    /**
     * A person's request to write stock up or down. Refuses {@code COUNT_VARIANCE} ({@code VALIDATION_FAILED}): only a
     * cycle count asks for that, through {@link #requestCountVariance}.
     */
    StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command);

    /** Submission's side of SCRUM-164: the variance of one counted line as pending COUNT_VARIANCE adjustment(s). */
    List<StockAdjustmentSummary> requestCountVariance(UUID cycleCountLineId, CountSlot slot, int delta,
                                                      UUID requestedBy, String note);

    StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId);

    StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason);

    /** The requester takes back an adjustment nobody has decided yet (SCRUM-459). */
    StockAdjustmentSummary withdraw(UUID adjustmentId, UUID userId);

    default Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId) {
        return findAdjustment(adjustmentId, BookVisibility.FULL);
    }

    /** As above; an adjustment of a slot under a count is not found for a reader who may not see the books (SCRUM-146). */
    Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId, BookVisibility visibility);

    default PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                            int page, int size, String sort) {
        return adjustments(status, sku, location, page, size, sort, BookVisibility.FULL);
    }

    /**
     * As above, without the adjustments of slots under a count (open, or its variance still pending approval) for a
     * reader who may not see the books: a pending {@code COUNT_VARIANCE} is a delta against them (SCRUM-146).
     */
    PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                    int page, int size, String sort, BookVisibility visibility);

    /** One line of the stock history, as the screen shows it. */
    record LedgerLine(UUID movementId, StockMovement.MovementType type, String sku, String lotNumber,
                      String fromLocation, String toLocation, int quantity, String status,
                      String toStatus, String referenceType, UUID referenceId, String reason, UUID actorId,
                      java.time.Instant occurredAt) {
    }

    default PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                            int page, int size) {
        return ledger(sku, location, type, page, size, BookVisibility.FULL);
    }

    /** As above, without the lines of slots under a count for a reader who may not see their books (SCRUM-146). */
    PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                    int page, int size, BookVisibility visibility);
}
