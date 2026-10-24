package com.stockflow.order.internal.domain;

/**
 * How accepted units are made good (SCRUM-260): what the customer asks for when opening a return, and - for the units
 * credited rather than replaced - how the money goes. Mapped {@code EnumType.STRING}.
 */
public enum ReturnResolution {

    /** In lại / đổi hàng: new goods of the same line, shipped as a free replacement order. */
    REPLACEMENT,

    /** Hoàn tiền: what is left after the order's open receivables are reduced is paid back (PENDING refunds). */
    REFUND,

    /** Giảm trừ công nợ: the order's open receivables are reduced, and the rest stays as the customer's credit. */
    CREDIT_NOTE;

    /** Whether this says what to do with money (the mode of a settlement), rather than with goods. */
    public boolean isMoney() {
        return this != REPLACEMENT;
    }
}
