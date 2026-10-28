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
     * The return's value first reduces what the order still owes on its receivables (the customer's own, never a
     * carrier's); the rest is refunded - PENDING refunds on the money the order received, newest first, never more of
     * a payment than is left of it - or kept as the customer's credit. Takes the order's lock first, as every money
     * path does.
     *
     * @throws com.stockflow.common.error.BusinessException {@code RETURN_REFUND_EXCEEDS_PAID} when the order received
     *         less money than the refund asks; {@code NOT_FOUND} for an unknown order
     * @throws IllegalStateException when the return was booked already - the caller decides a return once
     */
    SettleReturnResult settleReturn(SettleReturnCommand command);

    /**
     * The most a REFUND resolution of the order could book now (review of #195, N12): what the order still owes on the
     * customer's own receivables, plus what is left of the money it received after the refunds already asked of it.
     * Read only, nothing locked: {@link #settleReturn} decides under the order's lock; this lets Sales hear "credit it
     * instead" when they propose the resolution rather than after the accountant approved it.
     */
    java.math.BigDecimal refundCapacity(java.util.UUID orderId, java.util.UUID customerId, String currency);
}
