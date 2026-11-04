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
     * {@code OrderService.placeOrder} with server-authoritative prices: same idempotency on the request
     * id, same customer snapshot, payment terms and credit check, same all-or-nothing stock holds
     * (BR-ORD-001). Joins the caller's transaction.
     */
    OrderSummary placeQuotedOrder(PlaceOrderCommand command);
}
