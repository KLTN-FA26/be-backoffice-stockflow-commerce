package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** kltn-docs 14 BR-10 (2026-11-26): which price breaks need the Sales Manager, against which reference. */
class QuotePriceCheckTest {

    private static final String SKU = "LY12-ACME-01";
    private static final BigDecimal TEN = BigDecimal.TEN;

    private static QuoteLine line(int cartons, PriceTier... tiers) {
        return QuoteLine.carton(UUID.randomUUID(), 1, new Sku(SKU), UUID.randomUUID(), 50, cartons, List.of(tiers),
                null, null, "STANDARD", BigDecimal.TEN);
    }

    private static PriceTier tier(int from, int price) {
        return PriceTier.of(from, BigDecimal.valueOf(price));
    }

    private static QuotePriceCheck.Reference reference(QuotePriceCheck.Source source, int from, int price) {
        return new QuotePriceCheck.Reference(source, List.of(new QuotePriceCheck.Break(from, BigDecimal.valueOf(price))));
    }

    private static final QuotePriceCheck.Reference STANDARD_1000 =
            reference(QuotePriceCheck.Source.STANDARD_PRICE, 1, 1000);

    @Test
    void exactlyTheThresholdBelowIsSentAndOneDongMoreWaits() {
        QuotePriceCheck.Result atThreshold = QuotePriceCheck.of(List.of(line(5, tier(1, 900))), Map.of(),
                Map.of(SKU, STANDARD_1000), TEN);
        QuotePriceCheck.Result past = QuotePriceCheck.of(List.of(line(5, tier(1, 899))), Map.of(),
                Map.of(SKU, STANDARD_1000), TEN);

        assertThat(atThreshold.needsApproval()).isFalse();
        assertThat(atThreshold.lines().getFirst().tiers().getFirst().discountPercent()).isEqualByComparingTo("10.00");
        assertThat(past.needsApproval()).isTrue();
    }

    @Test
    void aBreakJustOverTheThresholdWaitsThoughItsShownFigureRoundsToIt() {
        QuotePriceCheck.Reference reference = reference(QuotePriceCheck.Source.STANDARD_PRICE, 1, 2009);

        // 1808 is 10.00498% below 2009: shown as 10.00, and still more than 10.
        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(5, tier(1, 1808))), Map.of(),
                Map.of(SKU, reference), TEN);

        assertThat(result.lines().getFirst().tiers().getFirst().discountPercent()).isEqualByComparingTo("10.00");
        assertThat(result.needsApproval()).isTrue();
    }

    @Test
    void aboveTheReferenceNeverWaits() {
        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(5, tier(1, 1800))), Map.of(),
                Map.of(SKU, STANDARD_1000), TEN);

        assertThat(result.needsApproval()).isFalse();
        assertThat(result.lines().getFirst().tiers().getFirst().discountPercent()).isEqualByComparingTo("-80.00");
    }

    @Test
    void noReferenceWaits() {
        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(5, tier(1, 5000))), Map.of(), Map.of(), TEN);

        assertThat(result.needsApproval()).isTrue();
        QuotePriceCheck.TierCheck check = result.lines().getFirst().tiers().getFirst();
        assertThat(check.referencePrice()).isNull();
        assertThat(check.source()).isNull();
    }

    @Test
    void theCustomersOwnPriceComesFirstAndTheStandardPriceCoversBelowItsFirstBreak() {
        QuotePriceCheck.Reference own = reference(QuotePriceCheck.Source.CUSTOMER_PRICE, 20, 2000);

        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(25, tier(5, 950), tier(20, 1900))),
                Map.of(SKU, own), Map.of(SKU, STANDARD_1000), TEN);

        List<QuotePriceCheck.TierCheck> tiers = result.lines().getFirst().tiers();
        assertThat(tiers.get(0).source()).isEqualTo(QuotePriceCheck.Source.STANDARD_PRICE);   // below the own price's 20
        assertThat(tiers.get(0).referencePrice()).isEqualByComparingTo("1000");
        assertThat(tiers.get(1).source()).isEqualTo(QuotePriceCheck.Source.CUSTOMER_PRICE);
        assertThat(tiers.get(1).referencePrice()).isEqualByComparingTo("2000");
        assertThat(result.needsApproval()).isFalse();
    }

    @Test
    void eachBreakIsReadAtItsOwnStart() {
        QuotePriceCheck.Reference standard = new QuotePriceCheck.Reference(QuotePriceCheck.Source.STANDARD_PRICE,
                List.of(new QuotePriceCheck.Break(1, new BigDecimal("1000")),
                        new QuotePriceCheck.Break(50, new BigDecimal("800"))));

        // 750 from 50 cartons is 6.25% below the 800 there, not 25% below the 1 000 of the cartons first proposed.
        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(10, tier(1, 1000), tier(50, 750))), Map.of(),
                Map.of(SKU, standard), TEN);

        assertThat(result.needsApproval()).isFalse();
    }

    @Test
    void aBreakBelowWhereTheReferenceStartsHasNone() {
        QuotePriceCheck.Reference fromTen = reference(QuotePriceCheck.Source.STANDARD_PRICE, 10, 1000);

        QuotePriceCheck.Result result = QuotePriceCheck.of(List.of(line(2, tier(1, 1200))), Map.of(),
                Map.of(SKU, fromTen), TEN);

        assertThat(result.needsApproval()).isTrue();
        assertThat(result.lines().getFirst().tiers().getFirst().referencePrice()).isNull();
    }

    @Test
    void theThresholdIsAPercentage() {
        assertThatThrownBy(() -> QuotePriceCheck.of(List.of(), Map.of(), Map.of(), BigDecimal.valueOf(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuotePriceCheck.of(List.of(), Map.of(), Map.of(), BigDecimal.valueOf(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
