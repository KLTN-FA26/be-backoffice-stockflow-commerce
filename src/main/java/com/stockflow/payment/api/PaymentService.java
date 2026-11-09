package com.stockflow.payment.api;

import java.util.UUID;

/**
 * THE public API of the payment module — the only package other modules may import.
 *
 * <p>Declares only what other modules call; every parameter and return type is a record or enum of
 * this package ({@code docs/adding-a-module.md} §1).</p>
 */
public interface PaymentService {

    /**
     * What the customer owes on receivables and whether any is overdue (SCRUM-431). Since 4B-6 it counts the
     * installments of their signed contracts too: what those paid after delivery still owe, not yet due included (15
     * BR-10), and any installment overdue.
     */
    CreditPosition creditPosition(UUID customerId);

    /**
     * 4B-6 (15 §4.1 step 1): the installments a contract's signature makes, written in the caller's transaction so that
     * they and the signature commit together - the credit check of a contract signed a moment later counts them, and
     * the order waiting for its installment on signing has it to pay at once. The one due on signing is due from the
     * signature. Opening a contract's installments twice opens nothing more.
     */
    void openInstallments(ContractInstallments contract);

    /** 4B-6: where the order's installments stand; {@link InstallmentStanding#none} for an order without them. */
    InstallmentStanding installmentStanding(UUID orderId);

    /**
     * The order's money in the ledger now (G2-3). The order module reads it under the order's row lock at the handover:
     * every allocation takes that lock first, so one committed a moment ago is counted.
     */
    OrderLedger ledgerOf(UUID orderId);

    /**
     * The account customers transfer to (SCRUM-193), from this deployment's configuration; empty while any of its parts
     * is not set, so a customer is never shown half an account - or a demo one.
     */
    java.util.Optional<CompanyBankAccount> companyBankAccount();
}
