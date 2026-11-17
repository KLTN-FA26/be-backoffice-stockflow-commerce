package com.stockflow.order.internal.domain;

/**
 * 4B-8 (kltn-docs 17 BR-28): why a delivery window moved. {@code ck_delivery_batch_window_change_reason} lists the same.
 */
public enum WindowChangeReason {
    /** The customer asked to move it, or agreed to take the goods early. */
    CUSTOMER_REQUEST,
    /** The seller is late, a production order for instance; late-delivery penalties are settled outside (D37). */
    SELLER_DELAY
}
