package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.PaymentTerm;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 03-state-machines §18, BR-ORD-007/008 and kltn-docs 14 BR-04, BR-07, BR-08 (4B-2) on the aggregate: lines of cartons
 * of the customer's own SKUs, priced per piece, each SKU's breaks read at its own cartons; a quote drafted before on
 * blanks and designs is kept to read (QD-16). Refusals leave the quote as it was.
 */
class QuoteTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final Instant VALID_UNTIL = NOW.plus(Duration.ofDays(14));
    private static final UUID CARTON = UUID.randomUUID();
    private static final List<PriceTier> TIERS = List.of(new PriceTier(50, Money.vnd(1800)),
            new PriceTier(10, Money.vnd(2000)), new PriceTier(100, Money.vnd(1650)));
    private final UUID sales = UUID.randomUUID();
    private final UUID customerUser = UUID.randomUUID();

    /**
     * Cartons of 50 pieces of {@code sku}: 10+ cartons at 2,000 VND a piece, 50+ at 1,800, 100+ at 1,650; VAT STANDARD,
     * 10% (4B-3).
     */
    private static QuoteLine line(int lineNo, String sku, int cartons) {
        return QuoteLine.carton(UUID.randomUUID(), lineNo, new Sku(sku), CARTON, 50, cartons, TIERS, null, null,
                "STANDARD", new BigDecimal("10"));
    }

    private static QuoteLine line(int lineNo, int cartons) {
        return line(lineNo, "LY12-ACME-01", cartons);
    }

    private static QuoteLine legacyLine(int lineNo, String blank, int units) {
        return QuoteLine.legacy(UUID.randomUUID(), lineNo, new Sku(blank), UUID.randomUUID(), "2 colours, front",
                units, List.of(new PriceTier(1000, Money.vnd(2000)), new PriceTier(5000, Money.vnd(1800))), null, null);
    }

    private static Quote.Offer offer(QuoteLine... lines) {
        return new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("30"), VALID_UNTIL, "Net of VAT", List.of(lines));
    }

    @Test
    void theRequestedDeliveryDateIsPartOfTheOfferAndOnlyADraftChangesIt() {
        java.time.LocalDate asked = java.time.LocalDate.of(2026, 12, 15);
        Quote quote = Quote.draft(UUID.randomUUID(), "QT-20261014-0002", UUID.randomUUID(), UUID.randomUUID(),
                new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("30"), VALID_UNTIL, "Net of VAT", asked,
                        List.of(line(1, 20))));
        assertThat(quote.offer().requestedDeliveryDate()).isEqualTo(asked);
        // The old five-argument offer is one without a date.
        assertThat(offer(line(1, 20)).requestedDeliveryDate()).isNull();

        quote.send(sales, NOW);
        assertThat(quote.offer().requestedDeliveryDate()).isEqualTo(asked);
        assertThatThrownBy(() -> quote.revise(offer(line(1, 20)))).isInstanceOf(BusinessException.class);
        assertThat(quote.offer().requestedDeliveryDate()).as("a sent quote keeps what was sent").isEqualTo(asked);

        // Changes requested, reopened: the next revision is a draft again, Sales may move the date.
        quote.requestChanges("Need it earlier", customerUser, NOW);
        quote.reopen();
        quote.revise(new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("30"), VALID_UNTIL, "Net of VAT",
                asked.minusDays(7), List.of(line(1, 20))));
        assertThat(quote.offer().requestedDeliveryDate()).isEqualTo(asked.minusDays(7));
    }

    private Quote draft() {
        return Quote.draft(UUID.randomUUID(), "QT-20261014-0001", UUID.randomUUID(), UUID.randomUUID(),
                offer(line(1, 20)));
    }

    private Quote sent() {
        Quote quote = draft();
        quote.send(sales, NOW);
        return quote;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void onlyTheDocumentedEdgesExist() {
        Map<QuoteStatus, Set<QuoteStatus>> allowed = Map.of(
                QuoteStatus.DRAFT, EnumSet.of(QuoteStatus.SENT, QuoteStatus.CANCELLED),
                QuoteStatus.SENT, EnumSet.of(QuoteStatus.ACCEPTED, QuoteStatus.CHANGES_REQUESTED, QuoteStatus.EXPIRED),
                QuoteStatus.CHANGES_REQUESTED, EnumSet.of(QuoteStatus.DRAFT),
                // CANCELLED: decision 2026-10-17, a SKU on the accepted quote stopped (Sales, with a reason).
                QuoteStatus.ACCEPTED, EnumSet.of(QuoteStatus.CONVERTED, QuoteStatus.CANCELLED),
                QuoteStatus.EXPIRED, EnumSet.noneOf(QuoteStatus.class),
                QuoteStatus.CANCELLED, EnumSet.noneOf(QuoteStatus.class),
                QuoteStatus.CONVERTED, EnumSet.noneOf(QuoteStatus.class));
        for (QuoteStatus from : QuoteStatus.values()) {
            for (QuoteStatus to : QuoteStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void orderedByTheCartonPricedByThePieceTheLowestTierIsTheMoqInCartons() {
        QuoteLine line = line(1, 20);
        assertThat(line.legacy()).isFalse();
        assertThat(line.measure()).isEqualTo(QuoteLine.Measure.CARTON);
        assertThat(line.unitsPerCarton()).isEqualTo(50);
        assertThat(line.units()).as("20 cartons of 50").isEqualTo(1000);
        assertThat(line.tiers()).extracting(PriceTier::minQuantity).containsExactly(10, 50, 100);
        assertThat(line.moq()).isEqualTo(10);
        assertThat(line.priceFor(10)).isEqualTo(Money.vnd(2000));
        assertThat(line.priceFor(49)).isEqualTo(Money.vnd(2000));
        assertThat(line.priceFor(50)).isEqualTo(Money.vnd(1800));
        assertThat(line.priceFor(250)).isEqualTo(Money.vnd(1650));
        // 14 BR-08: the line's total is cartons x pieces per carton x the price of a piece.
        assertThat(line.total(20, line.priceFor(20))).isEqualTo(Money.vnd(2_000_000));
        assertThatThrownBy(() -> line.priceFor(9)).extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThatThrownBy(() -> offer(line(1, 5))).as("a proposal below its own MOQ")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
    }

    @Test
    void tiersAndLinesAreValidated() {
        assertThatThrownBy(() -> new PriceTier(0, Money.vnd(100))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PriceTier.of(10, new BigDecimal("100.50")))
                .as("whole dong only, refused before Money would round it").isInstanceOf(BusinessException.class);
        assertThat(PriceTier.of(10, new BigDecimal("2500.0000")).unitPrice()).isEqualTo(Money.vnd(2500));
        assertThatThrownBy(() -> new PriceTier(10, Money.vnd(0))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 50, 20,
                List.of(new PriceTier(10, Money.vnd(2000)), new PriceTier(10, Money.vnd(1900))), null, null))
                .as("two tiers at one number of cartons").isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 1, 20, TIERS,
                null, null)).as("a carton of one piece is no carton").extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.CARTON_SPEC_MISSING);
        assertThatThrownBy(() -> QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 1000, 10_001,
                TIERS, null, null)).as("more pieces than an order line holds").extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> offer(line(1, 20), line(2, 30))).as("one line per SKU (14 BR-22)")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.PREPAID, new BigDecimal("30"), VALID_UNTIL, null,
                List.of(line(1, 20)))).as("a deposit share on a prepaid quote").isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("100"), VALID_UNTIL, null,
                List.of(line(1, 20)))).isInstanceOf(BusinessException.class);
    }

    @Test
    void anUnpricedLineIsExplicitAndCannotBeSent() {
        QuoteLine unpriced = QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 50, 5, List.of(),
                null, null);
        assertThat(unpriced.priced()).isFalse();
        assertThatThrownBy(() -> unpriced.priceFor(5)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThatThrownBy(unpriced::moq).extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_NOT_PRICED);

        // Below a priced line's MOQ is still refused; the unpriced line is not held to one.
        Quote.Offer offer = offer(unpriced);
        assertThat(offer.priced()).isFalse();
        Quote quote = Quote.draft(UUID.randomUUID(), "QT-4", UUID.randomUUID(), UUID.randomUUID(), offer);
        assertThatThrownBy(() -> quote.send(sales, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);

        quote.revise(offer(line(1, 20)));
        quote.send(sales, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void sendThenAcceptFixesCartonsAndPricesFromEachSkusOwnTiers() {
        QuoteLine first = line(1, "LY12-ACME-01", 20);
        QuoteLine second = line(2, "LY16-ACME-01", 10);
        Quote quote = Quote.draft(UUID.randomUUID(), "QT-1", UUID.randomUUID(), UUID.randomUUID(), offer(first, second));
        assertThat(quote.offer().proposedQuantity("LY12-ACME-01")).isEqualTo(20);
        quote.send(sales, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.sentBy()).isEqualTo(sales);

        quote.accept(Map.of(second.id(), 60), customerUser, NOW.plusSeconds(60));

        assertThat(quote.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(quote.respondedBy()).isEqualTo(customerUser);
        assertThat(first.acceptedQuantity()).isEqualTo(20);
        assertThat(first.acceptedUnits()).isEqualTo(1000);
        assertThat(first.acceptedUnitPrice()).as("its own 20 cartons: the first break").isEqualTo(Money.vnd(2000));
        assertThat(second.acceptedQuantity()).as("the customer picked more cartons").isEqualTo(60);
        assertThat(second.acceptedUnits()).isEqualTo(3000);
        assertThat(second.acceptedUnitPrice()).as("60 cartons reach the 50 break").isEqualTo(Money.vnd(1800));
    }

    @Test
    void skusAreNotCountedTogether() {
        // Two SKUs of 6 cartons each: each below its own MOQ of 10, whatever the two make together.
        assertThatThrownBy(() -> offer(line(1, "LY12-ACME-01", 6), line(2, "LY16-ACME-01", 6)))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);

        Quote quote = Quote.draft(UUID.randomUUID(), "QT-3", UUID.randomUUID(), UUID.randomUUID(),
                offer(line(1, "LY12-ACME-01", 10), line(2, "LY16-ACME-01", 10)));
        quote.send(sales, NOW);
        UUID first = quote.offer().lines().getFirst().id();
        assertThatThrownBy(() -> quote.accept(Map.of(first, 9), customerUser, NOW.plusSeconds(60)))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void acceptanceBelowTheMoqOrOfAnotherQuotesLineChangesNothing() {
        Quote quote = sent();
        UUID lineId = quote.offer().lines().getFirst().id();
        assertThatThrownBy(() -> quote.accept(Map.of(lineId, 9), customerUser, NOW))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThatThrownBy(() -> quote.accept(Map.of(lineId, 0), customerUser, NOW))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> quote.accept(Map.of(UUID.randomUUID(), 20), customerUser, NOW))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.offer().lines().getFirst().acceptedQuantity()).isNull();
    }

    @Test
    void aSentQuoteExpiresAtItsValidityAndCanNoLongerBeAnswered() {
        Quote quote = sent();
        assertThat(quote.statusAt(VALID_UNTIL.minusMillis(1))).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.statusAt(VALID_UNTIL)).isEqualTo(QuoteStatus.EXPIRED);
        assertThatThrownBy(() -> quote.accept(Map.of(), customerUser, VALID_UNTIL))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_EXPIRED);
        assertThatThrownBy(() -> quote.requestChanges("cheaper", customerUser, VALID_UNTIL))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_EXPIRED);
        assertThat(quote.expire(VALID_UNTIL.minusSeconds(1))).as("not yet").isFalse();
        assertThat(quote.expire(VALID_UNTIL)).isTrue();
        assertThat(quote.status()).isEqualTo(QuoteStatus.EXPIRED);
        assertThat(quote.expire(VALID_UNTIL.plusSeconds(1))).as("once").isFalse();

        Quote draft = draft();
        assertThat(draft.expire(VALID_UNTIL.plusSeconds(1))).as("a draft never expires").isFalse();
        assertThatThrownBy(() -> draft.send(sales, VALID_UNTIL)).as("never sent already expired")
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void changesRequestedKeepTheFeedbackAndReopenStartsTheNextRevision() {
        Quote quote = sent();
        assertThatThrownBy(() -> quote.requestChanges(" ", customerUser, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        quote.requestChanges("  1,800 at 20 cartons  ", customerUser, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.CHANGES_REQUESTED);
        assertThat(quote.customerFeedback()).isEqualTo("1,800 at 20 cartons");

        assertThatThrownBy(() -> quote.revise(offer(line(1, 20)))).as("only a draft is edited")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        quote.reopen();
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(quote.revision()).isEqualTo(2);
        assertThat(quote.customerFeedback()).isNull();
        quote.revise(offer(line(1, 20)));
        quote.send(sales, NOW.plusSeconds(10));
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void onlyADraftIsCancelledWithAReasonAndOnlyAnAcceptedQuoteConvertsOnce() {
        Quote draft = draft();
        assertThatThrownBy(() -> draft.cancel(" ")).isInstanceOf(BusinessException.class);
        draft.cancel("Customer went elsewhere");
        assertThat(draft.status()).isEqualTo(QuoteStatus.CANCELLED);

        Quote quote = sent();
        assertThatThrownBy(() -> quote.cancel("too late")).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        assertThatThrownBy(() -> quote.convert(UUID.randomUUID(), NOW)).as("not accepted")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        quote.accept(Map.of(), customerUser, NOW);
        UUID order = UUID.randomUUID();
        quote.convert(order, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.CONVERTED);
        assertThat(quote.convertedOrderId()).isEqualTo(order);
        assertThatThrownBy(() -> quote.convert(UUID.randomUUID(), NOW)).as("one order")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        assertThat(quote.convertedOrderId()).isEqualTo(order);
    }

    @Test
    void aQuoteOnBlanksAndDesignsIsKeptToReadNotSentAcceptedOrConverted() {
        // As its rows keep it: units of a blank, every design on the blank counted together (P5) - 600 + 400.
        QuoteLine logoA = legacyLine(1, "CUP-PP-500", 600);
        QuoteLine logoB = legacyLine(2, "CUP-PP-500", 400);
        Quote.Offer old = offer(logoA, logoB);
        assertThat(old.legacy()).isTrue();
        assertThat(logoA.unitsPerCarton()).isEqualTo(1);
        assertThat(logoA.units()).isEqualTo(600);
        assertThat(old.proposedQuantity("CUP-PP-500")).isEqualTo(1000);
        assertThat(logoA.total(600, logoA.priceFor(1000))).isEqualTo(Money.vnd(1_200_000));
        QuoteLine twin = QuoteLine.legacy(UUID.randomUUID(), 3, logoA.sku(), logoA.designSnapshotId(), null, 1000,
                logoA.tiers(), null, null);
        assertThatThrownBy(() -> offer(logoA, twin)).as("the same design on the same blank twice")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> offer(logoA, line(2, 20))).as("never a mix").isInstanceOf(IllegalArgumentException.class);

        Quote draft = Quote.draft(UUID.randomUUID(), "QT-5", UUID.randomUUID(), UUID.randomUUID(), old);
        assertThatThrownBy(() -> draft.send(sales, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_LINES_LEGACY);
        assertThat(draft.status()).isEqualTo(QuoteStatus.DRAFT);
        // Sales revises it onto the customer's own SKUs: every line replaced, then it goes.
        draft.revise(offer(line(1, 20)));
        draft.send(sales, NOW);
        assertThat(draft.status()).isEqualTo(QuoteStatus.SENT);

        Quote sentBefore = Quote.restore(UUID.randomUUID(), "QT-6", UUID.randomUUID(), UUID.randomUUID(),
                QuoteStatus.SENT, 1, offer(legacyLine(1, "CUP-PP-500", 1000)), NOW, sales, null, null, null, null,
                null, null);
        assertThatThrownBy(() -> sentBefore.accept(Map.of(), customerUser, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_LINES_LEGACY);
        assertThat(sentBefore.status()).isEqualTo(QuoteStatus.SENT);

        QuoteLine accepted = QuoteLine.legacy(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), UUID.randomUUID(), null,
                1000, logoA.tiers(), 1000, Money.vnd(2000));
        Quote acceptedBefore = Quote.restore(UUID.randomUUID(), "QT-7", UUID.randomUUID(), UUID.randomUUID(),
                QuoteStatus.ACCEPTED, 1, offer(accepted), NOW, sales, NOW, customerUser, null, null, null, null);
        assertThatThrownBy(() -> acceptedBefore.convert(UUID.randomUUID(), NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_LINES_LEGACY);
        acceptedBefore.cancel("Drafted on blanks; quoted again on the customer's SKUs");
        assertThat(acceptedBefore.status()).isEqualTo(QuoteStatus.CANCELLED);
    }

    @Test
    void theCustomerOnlySeesQuotesOnceSent() {
        assertThat(QuoteStatus.DRAFT.seenByCustomer()).isFalse();
        assertThat(QuoteStatus.CANCELLED.seenByCustomer()).isFalse();
        assertThat(EnumSet.complementOf(EnumSet.of(QuoteStatus.DRAFT, QuoteStatus.CANCELLED)))
                .allMatch(QuoteStatus::seenByCustomer);
    }

    @Test
    void aQuoteIsSentOnceEveryLineHasItsVatRateAndEveryFeeItsAmount() {
        QuoteLine untaxed = QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 50, 20, TIERS,
                null, null);
        QuoteLine taxed = QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), CARTON, 50, 20, TIERS, null,
                null, "STANDARD", new BigDecimal("10"));
        assertThat(taxed.taxOf(taxed.total(20, taxed.priceFor(20)))).as("10% of 2,000,000").isEqualTo(Money.vnd(200_000));

        Quote noRate = Quote.draft(UUID.randomUUID(), "QT-8", UUID.randomUUID(), UUID.randomUUID(), offer(untaxed));
        assertThatThrownBy(() -> noRate.send(sales, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_TAX_CLASS_MISSING);

        QuoteFee plate = new QuoteFee(UUID.randomUUID(), com.stockflow.order.api.FeeKind.PLATE_FEE,
                new Sku("LY12-ACME-01"), null, "STANDARD", new BigDecimal("10"));
        Quote unpricedFee = Quote.draft(UUID.randomUUID(), "QT-9", UUID.randomUUID(), UUID.randomUUID(),
                new Quote.Offer(PaymentTerm.PREPAID, null, VALID_UNTIL, null, null, List.of(taxed), List.of(plate)));
        assertThatThrownBy(() -> unpricedFee.send(sales, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        unpricedFee.revise(new Quote.Offer(PaymentTerm.PREPAID, null, VALID_UNTIL, null, null, List.of(taxed),
                List.of(new QuoteFee(plate.id(), plate.kind(), plate.sku(), Money.vnd(0), "STANDARD",
                        new BigDecimal("10")))));
        unpricedFee.send(sales, NOW);
        assertThat(unpricedFee.status()).as("a plate fee waived at 0 is a price").isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void feeLinesAreOneShippingFeeAndOnePlateFeePerSkuOnTheQuote() {
        QuoteFee shipping = new QuoteFee(UUID.randomUUID(), com.stockflow.order.api.FeeKind.SHIPPING_FEE, null,
                Money.vnd(300_000), "STANDARD", new BigDecimal("10"));
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.PREPAID, null, VALID_UNTIL, null, null,
                List.of(line(1, 20)), List.of(shipping, new QuoteFee(UUID.randomUUID(), shipping.kind(), null,
                        Money.vnd(1), "STANDARD", new BigDecimal("10"))))).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.PREPAID, null, VALID_UNTIL, null, null,
                List.of(line(1, 20)), List.of(new QuoteFee(UUID.randomUUID(), com.stockflow.order.api.FeeKind.PLATE_FEE,
                        new Sku("LY16-ACME-01"), null, null, null)))).as("a SKU not on the quote")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> new QuoteFee(UUID.randomUUID(), com.stockflow.order.api.FeeKind.SHIPPING_FEE, null,
                Money.vnd(0), "STANDARD", new BigDecimal("10"))).as("a shipping fee is more than zero")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuoteFee(UUID.randomUUID(), com.stockflow.order.api.FeeKind.SHIPPING_FEE,
                new Sku("LY12-ACME-01"), Money.vnd(1), "STANDARD", new BigDecimal("10"))).as("names no SKU")
                .isInstanceOf(BusinessException.class);
    }
}
