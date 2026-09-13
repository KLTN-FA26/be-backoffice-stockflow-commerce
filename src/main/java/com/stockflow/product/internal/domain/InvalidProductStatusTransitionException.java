package com.stockflow.product.internal.domain;

import com.stockflow.product.api.ProductStatus;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Thrown by {@link Product}'s transition methods when the requested move isn't on
 * {@link ProductStatus#canTransitionTo}'s table, or when a precondition for the move isn't met
 * (e.g. {@code submit()} without a category).
 *
 * <p>Extends {@code BusinessException} directly, not {@code IllegalStateException} — see the
 * javadoc on {@code GlobalExceptionHandler.handleIllegalInput}: that handler logs every
 * {@code IllegalStateException} at ERROR as if it were a bug, which a routine "wrong status"
 * rejection is not. {@code StockItem}/{@code Order} use {@code IllegalStateException} for the same
 * kind of guard — an existing inconsistency in this codebase, not a pattern to copy.</p>
 */
public class InvalidProductStatusTransitionException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidProductStatusTransitionException(ProductId productId, ProductStatus from, ProductStatus to) {
        super(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                "Product %s cannot move from %s to %s".formatted(productId, from, to));
    }

    public InvalidProductStatusTransitionException(ProductId productId, String reason) {
        super(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION,
                "Product %s %s".formatted(productId, reason));
    }
}
