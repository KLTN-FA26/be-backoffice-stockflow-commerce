package com.stockflow.payment.api;

/**
 * Books the money of a resolved return (SCRUM-260): the order module calls it in the transaction that resolves the
 * return, so the return is resolved exactly when its money is booked, and a refusal (more refund than the order
 * received) leaves the return as it was.
 *
 * <p>Its own narrow interface rather than a method on {@link PaymentService}: one caller needs it, and every module
 * that reads a credit position would otherwise depend on a method that moves money (as {@code PickConsumption} is
 * kept apart from {@code InventoryService}).</p>
 */
public interface ReturnSettlement {

    /**
     * Books a return's value (4B-10: without the money ledger). A refund is refused: no money of an order can be
     * refunded through a return until 4D-3 works it out from the installments. A credit is reported as credited and
     * settled outside the system (kltn-docs 15 BR-05, BR-06). Takes the order's lock first, as every money path does.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RETURN_REFUND_EXCEEDS_PAID} for a refund;
     *         {@code NOT_FOUND} for an unknown order
     */
    SettleReturnResult settleReturn(SettleReturnCommand command);

    /**
     * The most a REFUND resolution of the order could book now (review of #195, N12), so Sales hear "credit it instead"
     * when they propose the resolution: zero since the money ledger went (4B-10), until 4D-3.
     */
    java.math.BigDecimal refundCapacity(java.util.UUID orderId, java.util.UUID customerId, String currency);
}
