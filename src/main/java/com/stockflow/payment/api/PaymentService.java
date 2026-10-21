package com.stockflow.payment.api;

import java.util.UUID;

/**
 * THE public API of the payment module — the only package other modules may import.
 *
 * <p>Declares only what other modules call; every parameter and return type is a record or enum of
 * this package ({@code docs/adding-a-module.md} §1).</p>
 */
public interface PaymentService {

    /** What the customer owes on receivables and whether any is overdue (SCRUM-431). */
    CreditPosition creditPosition(UUID customerId);

    /**
     * The order's money in the ledger now (G2-3). The order module reads it under the order's row lock at the handover:
     * every allocation takes that lock first, so one committed a moment ago is counted.
     */
    OrderLedger ledgerOf(UUID orderId);
}
