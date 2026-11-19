package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WF04 G2 (kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04): Sales change an order's delivery address, chosen from the
 * customer's address book (14 §4.2 step 9), until its first carton is labelled - the "held ADDRESS, corrected, released"
 * of 08 §6. Every change is kept and shown in the order's history.
 */
public interface ShippingAddressChanges {

    /**
     * The order's delivery address becomes the customer's shipping address {@code shippingAddressId}; the same address
     * again changes nothing and records nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} (the order); {@code ADDRESS_NOT_FOUND} (not
     *         an address of the order's customer); {@code VALIDATION_FAILED} (a billing address); {@code ORDER_CANCELLED};
     *         {@code ORDER_ADDRESS_LOCKED} once a carton of the order is labelled, or in a status where the address
     *         does not change
     */
    OrderSummary change(UUID orderId, UUID shippingAddressId, UUID by);

    /** Every change of the order's delivery address, oldest first. */
    List<Change> changes(UUID orderId);

    /**
     * One change: the order's status then, the address before (null for none) and after, who and when.
     */
    record Change(OrderStatus orderStatus, OrderSummary.AddressSummary previous, OrderSummary.AddressSummary next,
                  UUID changedBy, Instant changedAt) {
    }
}
