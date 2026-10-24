package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.domain.StockMovement;

import java.util.List;
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

    /** See {@code InventoryService#receiveReturn}. */
    StockMove receiveReturn(com.stockflow.inventory.api.ReceiveReturnCommand command);

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

    StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command);

    StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId);

    StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason);

    /** The requester takes back an adjustment nobody has decided yet (SCRUM-459). */
    StockAdjustmentSummary withdraw(UUID adjustmentId, UUID userId);

    Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId);

    PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                    int page, int size, String sort);

    /** One line of the stock history, as the screen shows it. */
    record LedgerLine(UUID movementId, StockMovement.MovementType type, String sku, String lotNumber,
                      String fromLocation, String toLocation, int quantity, String status,
                      String toStatus, String referenceType, UUID referenceId, String reason, UUID actorId,
                      java.time.Instant occurredAt) {
    }

    PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                    int page, int size);
}
