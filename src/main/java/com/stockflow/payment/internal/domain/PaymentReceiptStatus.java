package com.stockflow.payment.internal.domain;

/**
 * kltn-docs 15 §5.3: a receipt is {@code RECORDED} when the accountant enters it from the bank statement and
 * {@code VOIDED} when it was wrong - kept as it was, with who voided it, when and why (BR-04). Mapped
 * {@code EnumType.STRING}.
 */
public enum PaymentReceiptStatus {
    RECORDED,
    VOIDED
}
