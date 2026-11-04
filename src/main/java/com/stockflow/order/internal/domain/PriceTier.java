package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Objects;

/**
 * A price break: from {@code minQuantity} units up, {@code unitPrice} per unit. Whole VND, positive
 * ({@code ck_quote_price_tier_price}), because order lines are VND and a fraction of a dong cannot be
 * invoiced.
 */
public record PriceTier(int minQuantity, Money unitPrice) {

    /**
     * From an amount as entered. Checked here, before {@link Money}: Money keeps VND at whole dong by
     * rounding, which would turn 2,500.50 into 2,501 without a word.
     */
    public static PriceTier of(int minQuantity, java.math.BigDecimal unitPrice) {
        if (unitPrice == null || unitPrice.signum() <= 0 || unitPrice.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quoted unit price is a positive whole VND amount");
        }
        return new PriceTier(minQuantity, new Money(unitPrice, Money.VND));
    }

    public PriceTier {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (minQuantity < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A price tier starts at 1 unit or more");
        }
        if (!Money.VND.equals(unitPrice.currency()) || unitPrice.amount().signum() <= 0
                || unitPrice.amount().stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A quoted unit price is a positive whole VND amount");
        }
    }
}
