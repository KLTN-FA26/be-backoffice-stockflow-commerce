package com.stockflow.payment.internal.domain;

/**
 * How money reached the business (kltn-docs 15 §4.5, 09 §4.3 step 4): a customer's bank transfer, or a carrier
 * remitting the balance it collected on delivery. Mapped {@code EnumType.STRING}.
 *
 * <p>{@link #RETURN_CREDIT} is the one entry that is not money arriving (SCRUM-260, V20261025020100): the credit a
 * resolved return gives the customer, booked in the same ledger because it settles what they owe exactly as a
 * transfer does (Nợ 5212 / Có 131). Its reference is the RMA number, never a bank statement line.</p>
 */
public enum TransferChannel {
    BANK_TRANSFER,
    CARRIER_REMITTANCE,
    RETURN_CREDIT;

    /** What a payment from this entry is announced as ({@code PaymentCaptured.method}). */
    public com.stockflow.contracts.PaymentMethod paymentMethod() {
        return switch (this) {
            case BANK_TRANSFER -> com.stockflow.contracts.PaymentMethod.BANK_TRANSFER;
            case CARRIER_REMITTANCE -> com.stockflow.contracts.PaymentMethod.COD;
            case RETURN_CREDIT -> com.stockflow.contracts.PaymentMethod.CREDIT_NOTE;
        };
    }
}
