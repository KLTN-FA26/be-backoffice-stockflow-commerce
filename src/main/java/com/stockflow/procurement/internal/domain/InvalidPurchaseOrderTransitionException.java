package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Thrown by every {@link PurchaseOrder} transition guard. Never {@code IllegalStateException} —
 * {@code GlobalExceptionHandler.handleIllegalInput} logs every one of those at ERROR as if it were
 * a bug, the same trap {@code product.internal.domain.InvalidProductStatusTransitionException}'s
 * javadoc documents.
 */
public class InvalidPurchaseOrderTransitionException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidPurchaseOrderTransitionException(PurchaseOrderId id, PurchaseOrderStatus from,
                                                   PurchaseOrderStatus to) {
        super(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION,
                "Purchase order %s cannot move from %s to %s".formatted(id, from, to));
    }

    public InvalidPurchaseOrderTransitionException(PurchaseOrderId id, String reason) {
        super(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION,
                "Purchase order %s %s".formatted(id, reason));
    }
}
