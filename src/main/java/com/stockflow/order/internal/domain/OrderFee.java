package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.order.api.FeeKind;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A fee line of an order, copied from its accepted quote when the order is placed and fixed from then on (kltn-docs
 * 14 BR-06, BR-09, BR-17, BR-20; team plan 4B-3): the plate fee of a SKU produced for the first time, or the shipping
 * fee. No cartons, nothing held; its VAT is its amount at its rate, rounded to the dong (BR-21).
 *
 * @param sku the SKU a plate fee is for; null on the shipping fee
 */
public record OrderFee(UUID id, FeeKind kind, Sku sku, Money amount, BigDecimal taxRate, Money taxAmount) {

    public OrderFee {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(taxAmount, "taxAmount");
        taxRate = Vat.rate(taxRate);
        if ((kind == FeeKind.PLATE_FEE) != (sku != null)) {
            throw new IllegalArgumentException("A plate fee names its SKU; the shipping fee names none");
        }
        if (amount.isNegative() || (kind == FeeKind.SHIPPING_FEE && amount.isZero())) {
            throw new IllegalArgumentException("A fee is not negative, and a shipping fee is more than zero");
        }
    }

    /** A fee as placed: its VAT computed from its amount and rate. */
    public static OrderFee of(UUID id, FeeKind kind, Sku sku, Money amount, BigDecimal taxRate) {
        return new OrderFee(id, kind, sku, amount, taxRate, Vat.of(amount, taxRate));
    }

    /** The amount with its VAT. */
    public Money gross() {
        return amount.plus(taxAmount);
    }
}
