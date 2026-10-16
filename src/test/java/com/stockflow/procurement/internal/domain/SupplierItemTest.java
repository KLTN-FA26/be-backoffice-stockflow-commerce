package com.stockflow.procurement.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.CommercialTerms;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

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
        return new SupplierItemTerms(code, new Money(price, Money.VND), moq, packSize, leadTimeDays);
    }

    private static void assertRefusedOn(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).fieldErrors())
                        .extracting(FieldError::field)
                        .containsExactly(field));
    }

    @Test
    void moqMustBeAMultipleOfThePackSize() {
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 5, 2, null), "moq");
    }

    @Test
    void moqAndPackSizeMustBeAtLeastOne() {
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 0, 1, null), "moq");
        assertRefusedOn(() -> terms(new BigDecimal("10.00"), 6, 0, null), "packSize");
    }

    @Test
    void priceMustBePositiveWithAtMostTwoDecimals() {
        // Money already rounds to its currency's scale, so the two-decimal rule only bites for a
        // currency with a wider scale (KWD has three) that NUMERIC(18,2) could not store exactly.
        var usd = Currency.getInstance("USD");
        var kwd = Currency.getInstance("KWD");
        assertRefusedOn(() -> new SupplierItemTerms("S", new Money(new BigDecimal("1.005"), kwd), 1, 1, null), "price");
        assertRefusedOn(() -> new SupplierItemTerms("S", new Money(BigDecimal.ZERO, usd), 1, 1, null), "price");
        assertRefusedOn(() -> new SupplierItemTerms("S", new Money(new BigDecimal("-1"), usd), 1, 1, null), "price");
        assertRefusedOn(() -> terms(new BigDecimal("0"), 1, 1, null), "price");
        assertRefusedOn(() -> terms(new BigDecimal("-1"), 1, 1, null), "price");
        assertThatCode(() -> new SupplierItemTerms("S", new Money(new BigDecimal("1.50"), usd), 1, 1, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aNullPriceIsRefusedOnThePriceField() {
        assertRefusedOn(() -> new SupplierItemTerms("S", null, 1, 1, null), "price");
    }

    @Test
    void leadTimeIsNullOrZeroTo365() {
        assertRefusedOn(() -> terms(new BigDecimal("10"), 1, 1, -1), "leadTimeDays");
        assertRefusedOn(() -> terms(new BigDecimal("10"), 1, 1, 366), "leadTimeDays");
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
        assertRefusedOn(() -> terms("x".repeat(51), new BigDecimal("10"), 1, 1, null), "supplierSkuCode");
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
        var usd = new SupplierItemTerms("S", new Money(new BigDecimal("10"), Currency.getInstance("USD")), 1, 1, null);
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
