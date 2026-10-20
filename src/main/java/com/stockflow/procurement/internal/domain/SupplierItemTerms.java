package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * What a supplier commits to for one SKU: price, minimum order, pack size and lead time.
 *
 * <p>Two ways in, on purpose. {@link #of} is for terms a caller is creating or changing, and checks
 * them. {@link #stored} rebuilds terms from a row already in the database and checks nothing: the
 * table is looser than these rules (no CHECK holds {@code moq} to a multiple of {@code pack_size} or
 * {@code lead_time_days} to 365 yet), and a single row the rules refuse must not stop the supplier's
 * catalog page from listing, nor the row from being corrected or removed. A class rather than a
 * record for the same reason: a record's canonical constructor is public, so there would be no way
 * to keep the validating path the only one a caller can reach by accident.</p>
 *
 * <p>The limits mirror the {@code supplier_items} CHECK constraints. Each violation names its field
 * and carries its own field code, whose translated message the exception handler puts beside the
 * input; the exception itself stays {@code VALIDATION_FAILED}, so the response is a 400. The price
 * comes in as the amount entered plus the currency, not as a {@link Money}: Money's constructor rounds
 * to the currency's scale, so a VND price of {@code 1500.50} would already be {@code 1501} and no
 * check made afterwards could see the lost digits. The price is therefore checked here, so nothing that
 * builds terms can skip it, against {@link PurchasePrices}: the rule a purchase order line's price meets
 * too, so a catalog price and a typed order price are held to the same standard.</p>
 *
 * <p>{@code moq % packSize == 0} is enforced here and not left to the order line: a MOQ that is not
 * a whole number of packs is a catalog that no quantity can satisfy.</p>
 */
public final class SupplierItemTerms {

    static final int MAX_SKU_CODE_LENGTH = 50;
    static final int MAX_LEAD_TIME_DAYS = 365;

    private final String supplierSkuCode;
    private final Money price;
    private final int moq;
    private final int packSize;
    private final Integer leadTimeDays;

    private SupplierItemTerms(String supplierSkuCode, Money price, int moq, int packSize, Integer leadTimeDays) {
        this.supplierSkuCode = supplierSkuCode;
        this.price = price;
        this.moq = moq;
        this.packSize = packSize;
        this.leadTimeDays = leadTimeDays;
    }

    /**
     * Terms a caller is creating or changing.
     *
     * @param supplierSkuCode the supplier's own code for the SKU; blank is stored as {@code null}
     * @param amount          the price as entered, checked before it is rounded into a {@link Money}
     * @param currency        the supplier's currency
     * @param leadTimeDays    {@code null} means "use the supplier's lead time"
     * @throws BusinessException {@code VALIDATION_FAILED}, with one field error naming the rule broken
     */
    public static SupplierItemTerms of(String supplierSkuCode, BigDecimal amount, Currency currency, int moq,
                                       int packSize, Integer leadTimeDays) {
        String code = supplierSkuCode == null || supplierSkuCode.isBlank() ? null : supplierSkuCode.strip();
        if (code != null && code.length() > MAX_SKU_CODE_LENGTH)
            throw refused("supplierSkuCode", ErrorCode.SUPPLIER_ITEM_SUPPLIER_SKU_CODE_TOO_LONG,
                    "Supplier SKU code must be at most " + MAX_SKU_CODE_LENGTH + " characters");
        Money price = price(amount, currency);
        if (moq < 1)
            throw refused("moq", ErrorCode.SUPPLIER_ITEM_MOQ_INVALID, "Minimum order quantity must be at least 1");
        if (packSize < 1)
            throw refused("packSize", ErrorCode.SUPPLIER_ITEM_PACK_SIZE_INVALID, "Pack size must be at least 1");
        if (moq % packSize != 0)
            throw refused("moq", ErrorCode.SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE,
                    "Minimum order quantity " + moq + " must be a multiple of the pack size " + packSize);
        if (leadTimeDays != null && (leadTimeDays < 0 || leadTimeDays > MAX_LEAD_TIME_DAYS))
            throw refused("leadTimeDays", ErrorCode.SUPPLIER_ITEM_LEAD_TIME_INVALID,
                    "Lead time must be between 0 and " + MAX_LEAD_TIME_DAYS + " days");
        return new SupplierItemTerms(code, price, moq, packSize, leadTimeDays);
    }

    /**
     * Terms exactly as a stored row holds them, unchecked: reading a row back is not the moment to
     * re-judge it (see the class comment). Never use it for values a caller supplied.
     */
    public static SupplierItemTerms stored(
            String supplierSkuCode, Money price, int moq, int packSize, Integer leadTimeDays) {
        return new SupplierItemTerms(supplierSkuCode, price, moq, packSize, leadTimeDays);
    }

    /**
     * {@link PurchasePrices}, the rule a purchase order line's price is held to as well, checked one
     * part at a time so the message names the part that failed.
     */
    private static Money price(BigDecimal amount, Currency currency) {
        if (currency == null || currency.getDefaultFractionDigits() < 0)
            throw refused("price", ErrorCode.SUPPLIER_ITEM_PRICE_INVALID, "Unsupported supplier currency "
                    + (currency == null ? null : currency.getCurrencyCode()));
        if (amount == null || amount.signum() <= 0)
            throw refused("price", ErrorCode.SUPPLIER_ITEM_PRICE_INVALID, "Price must be greater than zero");
        if (amount.compareTo(PurchasePrices.MAX) > 0)
            throw refused("price", ErrorCode.SUPPLIER_ITEM_PRICE_INVALID,
                    "Price must be at most " + PurchasePrices.MAX.toPlainString());
        int scale = PurchasePrices.maxScale(currency);
        if (amount.stripTrailingZeros().scale() > scale)
            throw refused("price", ErrorCode.SUPPLIER_ITEM_PRICE_INVALID,
                    "Price in " + currency.getCurrencyCode() + " must have at most " + scale + " decimal places");
        return new Money(amount, currency);
    }

    private static BusinessException refused(String field, ErrorCode fieldCode, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message, fieldCode.name())));
    }

    /** The supplier's own code for the SKU, or {@code null}. */
    public String supplierSkuCode() {
        return supplierSkuCode;
    }

    public Money price() {
        return price;
    }

    public int moq() {
        return moq;
    }

    public int packSize() {
        return packSize;
    }

    /** {@code null} means "use the supplier's lead time". */
    public Integer leadTimeDays() {
        return leadTimeDays;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SupplierItemTerms that
                && moq == that.moq
                && packSize == that.packSize
                && Objects.equals(supplierSkuCode, that.supplierSkuCode)
                && Objects.equals(price, that.price)
                && Objects.equals(leadTimeDays, that.leadTimeDays);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supplierSkuCode, price, moq, packSize, leadTimeDays);
    }

    @Override
    public String toString() {
        return "SupplierItemTerms[supplierSkuCode=%s, price=%s, moq=%d, packSize=%d, leadTimeDays=%s]"
                .formatted(supplierSkuCode, price, moq, packSize, leadTimeDays);
    }
}
