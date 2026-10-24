package com.stockflow.order.internal.domain;

/**
 * The state QC found a returned line's goods in ({@code ck_return_request_line_condition}). Recorded for the follow-up
 * with the customer and for reporting; what happens to the units is the line's disposition, decided with it.
 */
public enum ReturnItemCondition {
    UNOPENED,
    GOOD,
    DAMAGED,
    DEFECTIVE
}
