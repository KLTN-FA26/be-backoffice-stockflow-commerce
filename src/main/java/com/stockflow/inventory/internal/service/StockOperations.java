package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.domain.StockMovement;

import java.util.Optional;
import java.util.UUID;

/**
 * Moves, adjustments and the ledger (SCRUM-424, SCRUM-145). Not on the published port as a
 * whole: other modules move stock and ask for adjustments through {@code InventoryService}; deciding
 * an adjustment and browsing the ledger are back-office actions, reached only from the controllers.
 */
public interface StockOperations {

    StockMove move(MoveStockCommand command);

    StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command);

    StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId);

    StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason);

    Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId);

    PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                    int page, int size, String sort);

    /** One line of the stock history, as the screen shows it. */
    record LedgerLine(UUID movementId, StockMovement.MovementType type, String sku, String lotNumber,
                      String fromLocation, String toLocation, int quantity, String status,
                      String referenceType, UUID referenceId, String reason, UUID actorId,
                      java.time.Instant occurredAt) {
    }

    PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                    int page, int size);
}
