package com.stockflow.payment.internal.domain;

/**
 * When an installment falls due (kltn-docs 15 §4.1 step 2): at the signature, when the order is released to be shipped
 * ({@code BEFORE_DELIVERY}, which has no due day and blocks the handover until paid, BR-08), or when the goods are
 * delivered ({@code AFTER_DELIVERY}, the customer's receivable, due N days later, BR-09). The same names as the
 * contract's plan ({@code ordering.contract_payment_installment.milestone}).
 */
public enum InstallmentMilestone {
    ON_SIGNING,
    BEFORE_DELIVERY,
    AFTER_DELIVERY
}
