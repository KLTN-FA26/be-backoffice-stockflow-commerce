package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.List;

/**
 * What a supplier commits to for one SKU: price, minimum order, pack size and lead time.
 *
 * <p>The limits mirror the {@code supplier_items} column types and CHECK constraints, so a value
 * that gets past this constructor can never be refused by the database later as an opaque 500.
 * Each violation names its field, because the catalog form puts the message beside the input.</p>
 *
 * <p>{@code moq % packSize == 0} is enforced here and not left to the order line: a MOQ that is not
 * a whole number of packs is a catalog that no quantity can satisfy.</p>
 *
 * @param supplierSkuCode the supplier's own code for the SKU; blank is stored as {@code null}
 * @param leadTimeDays    {@code null} means "use the supplier's lead time"
 */
public record SupplierItemTerms(
        String supplierSkuCode, Money price, int moq, int packSize, Integer leadTimeDays) {

    static final int MAX_SKU_CODE_LENGTH = 50;
    static final int MAX_PRICE_SCALE = 2;
    static final int MAX_LEAD_TIME_DAYS = 365;

    public SupplierItemTerms {
        supplierSkuCode =
                supplierSkuCode == null || supplierSkuCode.isBlank() ? null : supplierSkuCode.strip();
        if (supplierSkuCode != null && supplierSkuCode.length() > MAX_SKU_CODE_LENGTH)
            throw refused("supplierSkuCode", "Supplier SKU code must be at most 50 characters");
        if (price == null || price.amount().signum() <= 0)
            throw refused("price", "Price must be greater than zero");
        // Money already rounds to its currency's scale; this catches a currency wider than the
        // NUMERIC(18,2) column rather than silently losing digits on insert.
        if (price.amount().scale() > MAX_PRICE_SCALE)
            throw refused("price", "Price must have at most 2 decimal places");
        if (moq < 1) throw refused("moq", "Minimum order quantity must be at least 1");
        if (packSize < 1) throw refused("packSize", "Pack size must be at least 1");
        if (moq % packSize != 0)
            throw refused("moq", "Minimum order quantity must be a multiple of the pack size");
        if (leadTimeDays != null && (leadTimeDays < 0 || leadTimeDays > MAX_LEAD_TIME_DAYS))
            throw refused("leadTimeDays", "Lead time must be between 0 and 365 days");
    }

    private static BusinessException refused(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                message,
                List.of(new FieldError(field, message, ErrorCode.VALIDATION_FAILED.name())));
    }
}
