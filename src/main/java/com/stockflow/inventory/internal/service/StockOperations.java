package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
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

    StockMove receive(ReceiveStockCommand command);

    /** See {@code InventoryService#receiveReturn}. */
    StockMove receiveReturn(com.stockflow.inventory.api.ReceiveReturnCommand command);

    /** See {@code InventoryService#countReceivingPackages}. */
    List<StockPackageView> countReceivingPackages(CountReceivingPackagesCommand command);

    /** See {@code InventoryService#receivingPackagesOf}. */
    Map<UUID, List<StockPackageView>> receivingPackagesOf(Collection<UUID> receiptLineIds);

    /** Loose stock out of quarantine, for a customer return's decision only; goes with team plan 4D-2. */
    StockMove reclassify(ReclassifyStockCommand command);

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
