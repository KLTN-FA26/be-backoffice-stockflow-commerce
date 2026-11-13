package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VAT of one line (kltn-docs 14 BR-21; team plan 4B-3): the line's total times its rate, rounded to the dong (half up),
 * line by line - a document's tax is the sum of its lines' taxes, never the tax of its sum.
 */
public final class Vat {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Vat() {
    }

    /** A rate as a percentage: 0 to below 100, two decimals at most ({@code ck_*_tax}). */
    public static BigDecimal rate(BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) >= 0
                || percent.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A VAT rate is 0 to below 100, two decimals at most");
        }
        return percent.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** The VAT of {@code net} at {@code percent}, in the net amount's currency, rounded to its smallest unit. */
    public static Money of(Money net, BigDecimal percent) {
        BigDecimal rate = rate(percent);
        int digits = net.currency().getDefaultFractionDigits();
        return new Money(net.amount().multiply(rate).divide(HUNDRED, digits, RoundingMode.HALF_UP), net.currency());
    }
}
