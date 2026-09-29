package com.stockflow.catalog.internal.domain;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.math.BigDecimal;
import com.stockflow.common.domain.Money;
public record SellingPrice(BigDecimal amount, String currency) {
    public SellingPrice {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0
                || amount.precision()-amount.scale() > 16 || !"VND".equals(currency))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        // Validate before Money normalizes scale: never silently round the agreed selling price.
        amount = new Money(amount, Money.VND).amount();
    }
}
