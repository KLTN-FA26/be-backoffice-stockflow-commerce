package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;

/**
 * Placing the order an accepted quote becomes (03-state-machines §18 ACCEPTED → CONVERTED, F-CART step
 * 06). Internal to the order module on purpose: the line prices on the command are trusted, so only the
 * quote service — which takes them from the accepted quote — may call it.
 */
public interface QuotedOrders {

    /**
     * A DRAFT order for the accepted quote's contract, at the quote's prices: idempotent on the request id, with the
     * customer's contact and addresses frozen on it. No stock is held and no terms or credit are checked here (the
     * contract does that). Joins the caller's transaction.
     */
    OrderSummary placeQuotedOrder(PlaceOrderCommand command);
}
