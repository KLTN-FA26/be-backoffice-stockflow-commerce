package com.stockflow.order.internal.domain;

/**
 * How a return is made good: what the customer asks for when opening it, and the mode of the settlement Sales
 * propose. Mapped {@code EnumType.STRING}; {@code ck_return_request_resolution}, {@code ck_return_request_money_mode}.
 *
 * <p>One value since team plan 4D-3: goods that come back are refunded (kltn-docs 17 §4.10 step 40). A free
 * replacement order and a credit kept for the customer are gone - an exchange is a new quote (step 41), and nothing
 * stays as the customer's credit (15 BR-05); V20261114000200 moved every row off them.</p>
 */
public enum ReturnResolution {

    /** Hoàn tiền: the goods' value comes off what the installments still owe; what was paid is refunded. */
    REFUND
}
