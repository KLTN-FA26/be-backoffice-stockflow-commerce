package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.CommercialTerms;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_LEAD_TIME_INVALID;
import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_MOQ_INVALID;
import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE;
import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_PACK_SIZE_INVALID;
import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_PRICE_INVALID;
import static com.stockflow.common.error.ErrorCode.SUPPLIER_ITEM_SUPPLIER_SKU_CODE_TOO_LONG;
import static com.stockflow.procurement.internal.domain.OrderQuantityViolation.BELOW_MOQ;
import static com.stockflow.procurement.internal.domain.OrderQuantityViolation.NOT_PACK_MULTIPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupplierItemTest {
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID ITEM = UUID.randomUUID();

    private static SupplierItemTerms terms(BigDecimal price, int moq, int packSize, Integer leadTimeDays) {
        return terms("SKU-1", price, moq, packSize, leadTimeDays);
    }

    private static SupplierItemTerms terms(
            String code, BigDecimal price, int moq, int packSize, Integer leadTimeDays) {
        return SupplierItemTerms.of(code, new Money(price, Money.VND), moq, packSize, leadTimeDays);
    }

    /** The exception stays VALIDATION_FAILED (a 400); the field error carries the rule's own code. */
    private static void assertRefusedOn(Runnable action, String field, ErrorCode fieldCode) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                assertThat(error.field()).isEqualTo(field);
                assertThat(error.code()).isEqualTo(fieldCode.name());
            });
        });
    }

    @Test
    void moqMustBeAMultipleOfThePackSize() {
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 5, 2, null), "moq",
                SUPPLIER_ITEM_MOQ_NOT_PACK_MULTIPLE);
    }

    @Test
    void moqAndPackSizeMustBeAtLeastOne() {
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 0, 1, null), "moq", SUPPLIER_ITEM_MOQ_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 6, 0, null), "packSize",
                SUPPLIER_ITEM_PACK_SIZE_INVALID);
    }

    @Test
    void priceMustBePositiveWithAtMostTwoDecimals() {
        // Money already rounds to its currency's scale, so the two-decimal rule only bites for a
        // currency with a wider scale (KWD has three) that NUMERIC(18,2) could not store exactly.
        var usd = Currency.getInstance("USD");
        var kwd = Currency.getInstance("KWD");
        assertRefusedOn(() -> SupplierItemTerms.of("S", new Money(new BigDecimal("1.005"), kwd), 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> SupplierItemTerms.of("S", new Money(BigDecimal.ZERO, usd), 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> SupplierItemTerms.of("S", new Money(new BigDecimal("-1"), usd), 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("0"), 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("-1"), 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
        assertThatCode(() -> SupplierItemTerms.of("S", new Money(new BigDecimal("1.50"), usd), 1, 1, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullPriceIsRefusedOnThePriceField() {
        assertRefusedOn(() -> SupplierItemTerms.of("S", null, 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
    }

    @Test
    void leadTimeIsNullOrZeroTo365() {
        assertRefusedOn(() -> terms(new BigDecimal("10"), 1, 1, -1), "leadTimeDays", SUPPLIER_ITEM_LEAD_TIME_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("10"), 1, 1, 366), "leadTimeDays", SUPPLIER_ITEM_LEAD_TIME_INVALID);
        assertThat(terms(new BigDecimal("10"), 1, 1, null).leadTimeDays()).isNull();
        assertThat(terms(new BigDecimal("10"), 1, 1, 0).leadTimeDays()).isZero();
        assertThat(terms(new BigDecimal("10"), 1, 1, 365).leadTimeDays()).isEqualTo(365);
    }

    @Test
    void blankSupplierSkuCodeBecomesNullAndOthersAreStripped() {
        assertThat(terms("  ", new BigDecimal("10"), 1, 1, null).supplierSkuCode()).isNull();
        assertThat(terms(null, new BigDecimal("10"), 1, 1, null).supplierSkuCode()).isNull();
        assertThat(terms(" AB-1 ", new BigDecimal("10"), 1, 1, null).supplierSkuCode()).isEqualTo("AB-1");
    }

    @Test
    void supplierSkuCodeIsAtMostFiftyCharacters() {
        assertThatCode(() -> terms("x".repeat(50), new BigDecimal("10"), 1, 1, null))
                .doesNotThrowAnyException();
        assertRefusedOn(() -> terms("x".repeat(51), new BigDecimal("10"), 1, 1, null), "supplierSkuCode",
                SUPPLIER_ITEM_SUPPLIER_SKU_CODE_TOO_LONG);
    }

    @Test
    void messagesAreBuiltFromTheLimits() {
        assertThatThrownBy(() -> terms(new BigDecimal("10"), 1, 1, SupplierItemTerms.MAX_LEAD_TIME_DAYS + 1))
                .hasMessageContaining(String.valueOf(SupplierItemTerms.MAX_LEAD_TIME_DAYS));
        assertThatThrownBy(() -> terms("x".repeat(SupplierItemTerms.MAX_SKU_CODE_LENGTH + 1), new BigDecimal("10"), 1,
                1, null)).hasMessageContaining(String.valueOf(SupplierItemTerms.MAX_SKU_CODE_LENGTH));
    }

    @Test
    void storedTermsAreRebuiltAsTheRowHoldsThemWithoutValidation() {
        // A row the form would refuse: MOQ not a whole number of packs, lead time past 365, a code
        // past 50 characters. Reading it back must not throw, or one row blocks the whole catalog.
        var price = new Money(new BigDecimal("10"), Money.VND);
        var stored = SupplierItemTerms.stored("x".repeat(51), price, 5, 2, 366);

        assertThat(stored.supplierSkuCode()).hasSize(51);
        assertThat(stored.moq()).isEqualTo(5);
        assertThat(stored.packSize()).isEqualTo(2);
        assertThat(stored.leadTimeDays()).isEqualTo(366);
        assertThatCode(() -> SupplierItemTerms.stored(null, price, 0, 0, -1)).doesNotThrowAnyException();

        var item = new SupplierItem(UUID.randomUUID(), SUPPLIER, ITEM, stored, false, 3L, null, null);
        assertThat(item.orderQuantityViolations(6)).isEmpty();
        assertThat(item.update(terms(new BigDecimal("10"), 6, 2, 30), false)).isEmpty();
        assertThat(item.terms().leadTimeDays()).isEqualTo(30);
    }

    @Test
    void createAssignsAnIdAndVersionZero() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null), true);
        assertThat(item.id()).isNotNull();
        assertThat(item.version()).isZero();
        assertThat(item.supplierId()).isEqualTo(SUPPLIER);
        assertThat(item.inventoryItemId()).isEqualTo(ITEM);
        assertThat(item.preferred()).isTrue();
    }

    @Test
    void orderQuantityViolations() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 12, 6, null), false);
        assertThat(item.orderQuantityViolations(12)).isEmpty();
        assertThat(item.orderQuantityViolations(18)).isEmpty();
        assertThat(item.orderQuantityViolations(6)).containsExactly(BELOW_MOQ);
        assertThat(item.orderQuantityViolations(13)).containsExactly(NOT_PACK_MULTIPLE);
        assertThat(item.orderQuantityViolations(7)).containsExactlyInAnyOrder(BELOW_MOQ, NOT_PACK_MULTIPLE);
    }

    @Test
    void updateReportsAPriceChangeOnlyWhenTheAmountDiffers() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10.00"), 1, 1, null), false);

        assertThat(item.update(terms(new BigDecimal("10.0"), 2, 1, null), true)).isEmpty();
        assertThat(item.terms().moq()).isEqualTo(2);
        assertThat(item.preferred()).isTrue();

        var change = item.update(terms(new BigDecimal("12.50"), 2, 1, null), true);
        assertThat(change).contains(new PriceChange(
                new Money(new BigDecimal("10.00"), Money.VND), new Money(new BigDecimal("12.50"), Money.VND)));
        assertThat(item.terms().price()).isEqualTo(new Money(new BigDecimal("12.50"), Money.VND));
    }

    @Test
    void updateRefusesACurrencyChange() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null), false);
        var usd = SupplierItemTerms.of("S", new Money(new BigDecimal("10"), Currency.getInstance("USD")), 1, 1, null);
        assertThatThrownBy(() -> item.update(usd, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void effectiveLeadTimeFallsBackToTheSupplierThenTheDefault() {
        var withLead = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, 3), false);
        var without = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null), false);
        assertThat(withLead.effectiveLeadTimeDays(9)).isEqualTo(3);
        assertThat(without.effectiveLeadTimeDays(9)).isEqualTo(9);
        assertThat(without.effectiveLeadTimeDays(null)).isEqualTo(CommercialTerms.LEAD_DAYS);
    }
}
