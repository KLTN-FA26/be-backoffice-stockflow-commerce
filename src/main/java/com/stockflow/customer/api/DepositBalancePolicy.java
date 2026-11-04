package com.stockflow.customer.api;

/**
 * How the balance of a customer's deposit orders is collected (kltn-docs 15 §4.2 step 3, "theo chính sách của
 * khách"; G2-3): by transfer before the goods are handed to the carrier, or collected by the carrier on delivery
 * (09 §2, the amount printed on the label, 15 BR-08). Part of the customer's terms when deposits are allowed; each
 * order keeps the policy it was sold on.
 *
 * <p>The third way of 15 §4.2 - the balance becomes a receivable on credit (ON_CREDIT, lead's decision 2026-10-21,
 * BALCREDIT) - needs CREDIT among the customer's allowed terms; the remaining balance is checked against the credit limit
 * when an order is placed.</p>
 */
public enum DepositBalancePolicy {
    BEFORE_DELIVERY,
    ON_DELIVERY,
    ON_CREDIT
}
