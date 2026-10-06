package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderStatus;

/**
 * The order is not in a status that allows the requested move — a cancel after shipment, a second
 * cancel, a payment against a cancelled order.
 *
 * <p>A {@link BusinessException} on purpose. These used to be {@code IllegalStateException}, which
 * {@code GlobalExceptionHandler} answers with {@code 400 VALIDATION_FAILED}: the client was told
 * its <i>input</i> was malformed when the input was fine and the <i>state</i> was the problem, and
 * it logged a stack trace at ERROR for what is an ordinary business refusal. {@code 409 CONFLICT}
 * is what tells a client "retrying the same request will not help until the order changes".</p>
 *
 * <p>Programming errors — submitting an order whose lines hold no reservation, attaching stock
 * twice — stay {@code IllegalStateException}: those are bugs in the caller, not a state the user
 * reached.</p>
 */
public class InvalidOrderTransitionException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidOrderTransitionException(OrderNumber orderNumber, OrderStatus from, OrderStatus to) {
        super(ErrorCode.CONFLICT,
                "Order %s cannot move from %s to %s".formatted(orderNumber, from, to));
    }

    public InvalidOrderTransitionException(OrderNumber orderNumber, OrderStatus from, String hint) {
        super(ErrorCode.CONFLICT,
                "Order %s cannot be cancelled from status %s; %s".formatted(orderNumber, from, hint));
    }
}
