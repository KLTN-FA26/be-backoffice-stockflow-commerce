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
     * 4B-8 (15 BR-08, 09 BR-18, 17 §4.5): what must be paid before the goods of one delivery batch leave - the
     * installments on signing, and those before delivery tied to that batch or to none. A batch's handover waits for
     * its own installment, not for those of the batches after it.
     */
    java.math.BigDecimal dueBeforeHandover(UUID orderId, UUID deliveryBatchId);

    /**
     * 4B-9 (15 §4.6 step 18, BR-15): a contract order was cancelled. Its installments not paid in full are cancelled;
     * the money collected on them pays the fee first, the rest is to be refunded, and a shortfall becomes the
     * cancellation fee item, due from the cancellation day. Written in the caller's transaction, after the order's row
     * lock (orders, then installments - the order every money movement keeps), so the cancellation and its money commit
     * together. Settling a cancellation twice settles nothing more.
     *
     * @return empty for an order without installments (no signed contract)
     */
    java.util.Optional<OrderSettlementResult> settleCancellation(SettleCancellationCommand command);

    /**
     * 4B-9 (15 §4.6 step 19, BR-16): the rest of a contract order was closed. What its installments still owe is
     * lowered by the goods closed - those tied to an undelivered batch first, then the latest - so the customer never
     * pays more than the goods received and the fees; what was paid above that pays the closure's fee first and the
     * rest is to be refunded; a shortfall becomes the cancellation fee item. In the caller's transaction, after the
     * order's lock; once per closure.
     *
     * @return empty for an order without installments
     */
    java.util.Optional<OrderSettlementResult> settleRemainderClosure(SettleRemainderClosureCommand command);

    /**
     * The account customers transfer to (SCRUM-193), from this deployment's configuration; empty while any of its parts
     * is not set, so a customer is never shown half an account - or a demo one.
     */
    java.util.Optional<CompanyBankAccount> companyBankAccount();
}
