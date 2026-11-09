package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.FeeKind;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A fee line of a quote (kltn-docs 14 BR-09, BR-17, BR-20, BR-21; team plan 4B-3): the plate fee of a SKU on the quote
 * produced for the first time - the system adds it, Sales enters its amount - or the shipping fee Sales adds and prices.
 * No cartons: it counts towards no MOQ or tier. Its VAT rate comes with it: the SKU's product tax class for a plate fee,
 * STANDARD for the shipping fee (D31).
 *
 * @param sku     the SKU a plate fee is for; null on the shipping fee
 * @param amount  whole VND; null for a plate fee whose amount Sales has not entered yet - the quote is not sent so
 * @param taxRate null while the SKU's product has no tax class - the quote is not sent so
 */
public record QuoteFee(UUID id, FeeKind kind, Sku sku, Money amount, String taxClass, BigDecimal taxRate) {

    public QuoteFee {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        if ((kind == FeeKind.PLATE_FEE) != (sku != null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A plate fee names the SKU it is for; the shipping fee names none");
        }
        if (amount != null && (!Money.VND.equals(amount.currency()) || amount.isNegative()
                || amount.amount().stripTrailingZeros().scale() > 0
                || (kind == FeeKind.SHIPPING_FEE && amount.isZero()))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, kind == FeeKind.SHIPPING_FEE
                    ? "A shipping fee is a positive whole VND amount" : "A plate fee is a whole VND amount, 0 or more");
        }
        if (kind == FeeKind.SHIPPING_FEE && amount == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A shipping fee is added with its amount");
        }
        if ((taxClass == null) != (taxRate == null)) {
            throw new IllegalArgumentException("A fee has its tax class and rate, or neither");
        }
        taxRate = taxRate == null ? null : Vat.rate(taxRate);
    }

    /** Whether Sales entered its amount. */
    public boolean priced() {
        return amount != null;
    }

    /** Its VAT; null until it has both its amount and its rate. */
    public Money tax() {
        return amount == null || taxRate == null ? null : Vat.of(amount, taxRate);
    }
}
