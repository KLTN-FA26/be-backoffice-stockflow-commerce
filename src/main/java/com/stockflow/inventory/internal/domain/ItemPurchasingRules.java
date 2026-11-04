package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemPurchasing;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The shape of an item's purchasing information, stated here so a bad value is a 400 naming its field;
 * {@code ck_inventory_items_reference_price}, {@code _lead_time} and {@code _moq} state it again. The
 * limits are the supplier catalog's (procurement {@code SupplierItemTerms}): price above zero with at
 * most 2 decimals, MOQ at least 1, lead time 0-365 days.
 */
public final class ItemPurchasingRules {

    public static final int MAX_LEAD_TIME_DAYS = 365;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999999999.99");

    private ItemPurchasingRules() {
    }

    public static ItemPurchasing validate(ItemPurchasing p) {
        BigDecimal price = p.referencePurchasePrice();
        String currency = p.referencePurchaseCurrency();
        if (price != null && (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2
                || price.compareTo(MAX_PRICE) > 0)) {
            throw invalid("referencePurchasePrice",
                    "referencePurchasePrice must be above zero, with at most 2 decimals");
        }
        if ((price == null) != (currency == null)) {
            throw invalid(price == null ? "referencePurchasePrice" : "referencePurchaseCurrency",
                    "referencePurchasePrice and referencePurchaseCurrency go together");
        }
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw invalid("referencePurchaseCurrency", "referencePurchaseCurrency is a 3-letter ISO 4217 code");
        }
        if (p.leadTimeDays() != null && (p.leadTimeDays() < 0 || p.leadTimeDays() > MAX_LEAD_TIME_DAYS)) {
            throw invalid("leadTimeDays", "leadTimeDays must be between 0 and " + MAX_LEAD_TIME_DAYS);
        }
        if (p.moq() != null && p.moq() < 1) {
            throw invalid("moq", "moq must be at least 1");
        }
        return p;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, ErrorCode.VALIDATION_FAILED.name())));
    }
}
