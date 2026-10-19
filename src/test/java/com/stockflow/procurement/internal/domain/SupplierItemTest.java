package com.stockflow.procurement.internal.domain;

import com.stockflow.common.domain.CommercialTerms;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
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
        return SupplierItemTerms.of(code, price, Money.VND, moq, packSize, leadTimeDays);
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
        // The amount is checked as entered, before Money rounds it: KWD has three decimals but the
        // column two, and VND has none, so 1500.50 VND would otherwise become 1501 without a word.
        var usd = Currency.getInstance("USD");
        var kwd = Currency.getInstance("KWD");
        assertRefusedOn(() -> SupplierItemTerms.of("S", new BigDecimal("1.005"), kwd, 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("1500.50"), 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> SupplierItemTerms.of("S", BigDecimal.ZERO, usd, 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> SupplierItemTerms.of("S", new BigDecimal("-1"), usd, 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("0"), 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(new BigDecimal("-1"), 1, 1, null), "price", SUPPLIER_ITEM_PRICE_INVALID);
        assertRefusedOn(() -> terms(SupplierItemTerms.MAX_PRICE.add(BigDecimal.ONE), 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        assertThatCode(() -> SupplierItemTerms.of("S", new BigDecimal("1.50"), usd, 1, 1, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> terms(new BigDecimal("1500.00"), 1, 1, null)).doesNotThrowAnyException();
    }

    @Test
    void aNullPriceOrAPseudoCurrencyIsRefusedOnThePriceField() {
        assertRefusedOn(() -> SupplierItemTerms.of("S", null, Money.VND, 1, 1, null), "price",
                SUPPLIER_ITEM_PRICE_INVALID);
        // XXX has no decimals at all (getDefaultFractionDigits() is -1): not a currency to price in.
        assertRefusedOn(() -> SupplierItemTerms.of("S", BigDecimal.TEN, Currency.getInstance("XXX"), 1, 1, null),
                "price", SUPPLIER_ITEM_PRICE_INVALID);
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

        var item = new SupplierItem(UUID.randomUUID(), SUPPLIER, ITEM, stored, 3L, null, null);
        assertThat(item.orderQuantityViolations(6)).isEmpty();
        assertThat(item.update(terms(new BigDecimal("10"), 6, 2, 30))).isEmpty();
        assertThat(item.terms().leadTimeDays()).isEqualTo(30);
    }

    @Test
    void createAssignsAnIdAndVersionZero() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null));
        assertThat(item.id()).isNotNull();
        assertThat(item.version()).isZero();
        assertThat(item.supplierId()).isEqualTo(SUPPLIER);
        assertThat(item.inventoryItemId()).isEqualTo(ITEM);
    }

    @Test
    void orderQuantityViolations() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 12, 6, null));
        assertThat(item.orderQuantityViolations(12)).isEmpty();
        assertThat(item.orderQuantityViolations(18)).isEmpty();
        assertThat(item.orderQuantityViolations(6)).containsExactly(BELOW_MOQ);
        assertThat(item.orderQuantityViolations(13)).containsExactly(NOT_PACK_MULTIPLE);
        assertThat(item.orderQuantityViolations(7)).containsExactlyInAnyOrder(BELOW_MOQ, NOT_PACK_MULTIPLE);
    }

    @Test
    void updateReportsAPriceChangeOnlyWhenTheAmountDiffers() {
        var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10.00"), 1, 1, null));

        assertThat(item.update(terms(new BigDecimal("10.0"), 2, 1, null))).isEmpty();
        assertThat(item.terms().moq()).isEqualTo(2);

        var change = item.update(terms(new BigDecimal("12"), 2, 1, null));
        assertThat(change).contains(new PriceChange(
                new Money(new BigDecimal("10.00"), Money.VND), new Money(new BigDecimal("12"), Money.VND)));
        assertThat(item.terms().price()).isEqualTo(new Money(new BigDecimal("12"), Money.VND));
    }

    @Test
    void anEditRepricesARowFromAnotherCurrencyIntoTheSuppliers() {
        // A carried row in USD for a VND supplier: the edit moves it to VND, and that is a price change
        // even though the number is the same, so its history closes the USD period.
        var usd = Currency.getInstance("USD");
        var stored = SupplierItemTerms.stored("S", new Money(new BigDecimal("10"), usd), 1, 1, null);
        var item = new SupplierItem(UUID.randomUUID(), SUPPLIER, ITEM, stored, 3L, Instant.EPOCH, null);

        var change = item.update(terms(new BigDecimal("10"), 1, 1, null));

        assertThat(change).contains(new PriceChange(
                new Money(new BigDecimal("10"), usd), new Money(new BigDecimal("10"), Money.VND)));
        assertThat(item.terms().price().currency()).isEqualTo(Money.VND);
    }

    @Test
    void onlyAnItemThatWasNeverStoredIsNew() {
        assertThat(SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null)).isNew())
                .isTrue();
        var stored = SupplierItemTerms.stored("S", new Money(BigDecimal.TEN, Money.VND), 1, 1, null);
        assertThat(new SupplierItem(UUID.randomUUID(), SUPPLIER, ITEM, stored, 0L, Instant.EPOCH, null).isNew())
                .isFalse();
    }

    @Test
    void effectiveLeadTimeFallsBackToTheSupplierThenTheDefault() {
        var withLead = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, 3));
        var without = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 1, 1, null));
        assertThat(withLead.effectiveLeadTimeDays(9)).isEqualTo(3);
        assertThat(without.effectiveLeadTimeDays(9)).isEqualTo(9);
        assertThat(without.effectiveLeadTimeDays(null)).isEqualTo(CommercialTerms.LEAD_DAYS);
    }
}
