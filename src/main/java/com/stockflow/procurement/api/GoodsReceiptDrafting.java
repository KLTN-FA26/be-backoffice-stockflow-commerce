package com.stockflow.procurement.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A DRAFT goods receipt with its count, in one transaction: what the goods-receipt screen does in two steps
 * ({@code POST /api/v1/goods-receipts}, then {@code PUT /{id}/lines}), with the same checks — the order receivable
 * and in the caller's warehouse scope (BR-SEC-002), each line's tolerance, lot and expiry, and a RECEIVING area of
 * that warehouse. Added for the assistant, which turns a delivery note read from a photo into such a draft once its
 * user has checked it (ADR-0011); nothing is confirmed here: the count stays a draft to confirm on the screen.
 *
 * <p>No permission is checked here, as on {@link ProcurementService}: the caller's endpoint carries the screen's
 * guards ({@code procurement-goods-receipts} CREATE and UPDATE).</p>
 */
public interface GoodsReceiptDrafting {

    /**
     * @throws com.stockflow.common.error.BusinessException what the two screen calls throw: PURCHASE_ORDER_NOT_FOUND,
     *         PURCHASE_ORDER_NOT_RECEIVABLE, a scope refusal, and the line errors, each field named
     */
    DraftedReceipt draft(DraftReceiptCommand command, UUID userId);

    /** A receipt to start against {@code purchaseOrderId}, with its lines counted. */
    record DraftReceiptCommand(UUID purchaseOrderId, String deliveryNote, String note, List<Line> lines) {

        public DraftReceiptCommand {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One counted line, as {@code PUT /{id}/lines} takes it. */
    record Line(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate, String locationCode,
                String note) {
    }

    /** The draft created. */
    record DraftedReceipt(UUID receiptId, String receiptNumber, UUID warehouseId) {
    }
}
