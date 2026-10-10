package com.stockflow.order.api;

import java.util.UUID;

/**
 * What a customer's cancellation did (SCRUM-460): the order was cancelled at once, or — once it has
 * been released to production or the warehouse — a request was opened for Sales to decide.
 *
 * @param requestId set when {@code result} is REQUESTED
 */
public record CancellationOutcome(Result result, UUID requestId, OrderSummary order) {

    public enum Result {
        CANCELLED,
        REQUESTED
    }
}
