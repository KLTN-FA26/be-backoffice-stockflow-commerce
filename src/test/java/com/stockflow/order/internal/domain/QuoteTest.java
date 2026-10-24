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

/** 03-state-machines §18, BR-ORD-007/008 on the aggregate; refusals leave the quote as it was. */
class QuoteTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final Instant VALID_UNTIL = NOW.plus(Duration.ofDays(14));
    private final UUID sales = UUID.randomUUID();
    private final UUID customerUser = UUID.randomUUID();

    private static QuoteLine line(int lineNo, int quantity) {
        return new QuoteLine(UUID.randomUUID(), lineNo, new Sku("CUP-PP-500"), UUID.randomUUID(), "2 colours, front",
                quantity, List.of(new PriceTier(5000, Money.vnd(1800)), new PriceTier(1000, Money.vnd(2000)),
                new PriceTier(10000, Money.vnd(1650))), null, null);
    }

    private static Quote.Offer offer(QuoteLine... lines) {
        return new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("30"), VALID_UNTIL, "Net of VAT", List.of(lines));
    }

    private Quote draft() {
        return Quote.draft(UUID.randomUUID(), "QT-20261014-0001", UUID.randomUUID(), UUID.randomUUID(),
                offer(line(1, 2000)));
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
                // CANCELLED: Sales withdraws a sent offer, or drops one whose changes it will not make.
                QuoteStatus.SENT, EnumSet.of(QuoteStatus.ACCEPTED, QuoteStatus.CHANGES_REQUESTED, QuoteStatus.EXPIRED,
                        QuoteStatus.CANCELLED),
                QuoteStatus.CHANGES_REQUESTED, EnumSet.of(QuoteStatus.DRAFT, QuoteStatus.CANCELLED),
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
    void tiersAreOrderedTheLowestIsTheMoqAndThePriceIsTheBreakReached() {
        QuoteLine line = line(1, 2000);
        assertThat(line.tiers()).extracting(PriceTier::minQuantity).containsExactly(1000, 5000, 10000);
        assertThat(line.moq()).isEqualTo(1000);
        assertThat(line.priceFor(1000)).isEqualTo(Money.vnd(2000));
        assertThat(line.priceFor(4999)).isEqualTo(Money.vnd(2000));
        assertThat(line.priceFor(5000)).isEqualTo(Money.vnd(1800));
        assertThat(line.priceFor(25000)).isEqualTo(Money.vnd(1650));
        assertThatThrownBy(() -> line.priceFor(999)).extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThatThrownBy(() -> offer(line(1, 500))).as("a proposal below its own MOQ")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
    }

    @Test
    void tiersAndLinesAreValidated() {
        assertThatThrownBy(() -> new PriceTier(0, Money.vnd(100))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PriceTier.of(10, new BigDecimal("100.50")))
                .as("whole dong only, refused before Money would round it").isInstanceOf(BusinessException.class);
        assertThat(PriceTier.of(10, new BigDecimal("2500.0000")).unitPrice()).isEqualTo(Money.vnd(2500));
        assertThatThrownBy(() -> new PriceTier(10, Money.vnd(0))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuoteLine(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), UUID.randomUUID(), null,
                1000, List.of(new PriceTier(1000, Money.vnd(2000)), new PriceTier(1000, Money.vnd(1900))), null, null))
                .as("two tiers at one quantity").isInstanceOf(BusinessException.class);
        QuoteLine twin = line(2, 2000);
        QuoteLine same = new QuoteLine(UUID.randomUUID(), 1, twin.blankSku(), twin.designSnapshotId(), null, 2000,
                twin.tiers(), null, null);
        assertThatThrownBy(() -> offer(same, twin)).as("the same design on the same blank twice")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.PREPAID, new BigDecimal("30"), VALID_UNTIL, null,
                List.of(line(1, 2000)))).as("a deposit share on a prepaid quote").isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Quote.Offer(PaymentTerm.DEPOSIT, new BigDecimal("100"), VALID_UNTIL, null,
                List.of(line(1, 2000)))).isInstanceOf(BusinessException.class);
    }

    @Test
    void anUnpricedLineIsExplicitAndCannotBeSent() {
        QuoteLine unpriced = new QuoteLine(UUID.randomUUID(), 1, new Sku("CUP-PP-500"), UUID.randomUUID(), null, 500,
                List.of(), null, null);
        assertThat(unpriced.priced()).isFalse();
        assertThatThrownBy(() -> unpriced.priceFor(500)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThatThrownBy(unpriced::moq).extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_NOT_PRICED);

        // Below a priced line's MOQ is still refused; the unpriced line is not held to one.
        Quote.Offer offer = offer(unpriced);
        assertThat(offer.priced()).isFalse();
        Quote quote = Quote.draft(UUID.randomUUID(), "QT-4", UUID.randomUUID(), UUID.randomUUID(), offer);
        assertThatThrownBy(() -> quote.send(sales, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_NOT_PRICED);
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);

        quote.revise(offer(line(1, 2000)));
        quote.send(sales, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void sendThenAcceptFixesQuantitiesAndPricesFromTheTiers() {
        QuoteLine first = line(1, 2000);
        QuoteLine second = line(2, 1000);
        Quote quote = Quote.draft(UUID.randomUUID(), "QT-1", UUID.randomUUID(), UUID.randomUUID(), offer(first, second));
        quote.send(sales, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.sentBy()).isEqualTo(sales);

        quote.accept(Map.of(second.id(), 6000), customerUser, NOW.plusSeconds(60));

        assertThat(quote.status()).isEqualTo(QuoteStatus.ACCEPTED);
        assertThat(quote.respondedBy()).isEqualTo(customerUser);
        // Two designs on one blank: 2,000 + 6,000 = 8,000 blanks pick the 5,000 break for both (P5).
        assertThat(first.acceptedQuantity()).isEqualTo(2000);
        assertThat(first.acceptedUnitPrice()).isEqualTo(Money.vnd(1800));
        assertThat(second.acceptedQuantity()).as("the customer picked a higher tier").isEqualTo(6000);
        assertThat(second.acceptedUnitPrice()).isEqualTo(Money.vnd(1800));
    }

    @Test
    void designsOnTheSameBlankAreCountedTogetherOtherBlanksAreNot() {
        // 600 with logo A + 400 with logo B = 1,000 blanks: at the MOQ, so the quote is valid.
        QuoteLine logoA = line(1, 600);
        QuoteLine logoB = line(2, 400);
        Quote.Offer offer = offer(logoA, logoB);
        assertThat(offer.proposedUnits("CUP-PP-500")).isEqualTo(1000);

        Quote quote = Quote.draft(UUID.randomUUID(), "QT-2", UUID.randomUUID(), UUID.randomUUID(), offer);
        quote.send(sales, NOW);
        quote.accept(Map.of(logoA.id(), 3000, logoB.id(), 2000), customerUser, NOW.plusSeconds(60));
        assertThat(logoA.acceptedUnitPrice()).as("5,000 blanks together").isEqualTo(Money.vnd(1800));
        assertThat(logoB.acceptedUnitPrice()).isEqualTo(Money.vnd(1800));

        // Another blank is not added in: 600 of one and 400 of another are each below 1,000.
        QuoteLine otherBlank = new QuoteLine(UUID.randomUUID(), 2, new Sku("CUP-PP-700"), UUID.randomUUID(), null, 400,
                logoA.tiers(), null, null);
        assertThatThrownBy(() -> offer(line(1, 600), otherBlank)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);

        // Accepting fewer than the MOQ in total is refused, whatever each line says.
        Quote again = Quote.draft(UUID.randomUUID(), "QT-3", UUID.randomUUID(), UUID.randomUUID(),
                offer(line(1, 600), line(2, 400)));
        again.send(sales, NOW);
        UUID first = again.offer().lines().getFirst().id();
        assertThatThrownBy(() -> again.accept(Map.of(first, 500), customerUser, NOW.plusSeconds(60)))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThat(again.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void acceptanceBelowTheMoqOrOfAnotherQuotesLineChangesNothing() {
        Quote quote = sent();
        UUID lineId = quote.offer().lines().getFirst().id();
        assertThatThrownBy(() -> quote.accept(Map.of(lineId, 999), customerUser, NOW))
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.QUOTE_BELOW_MOQ);
        assertThatThrownBy(() -> quote.accept(Map.of(UUID.randomUUID(), 2000), customerUser, NOW))
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
        quote.requestChanges("  1,800 at 2,000 units  ", customerUser, NOW);
        assertThat(quote.status()).isEqualTo(QuoteStatus.CHANGES_REQUESTED);
        assertThat(quote.customerFeedback()).isEqualTo("1,800 at 2,000 units");

        assertThatThrownBy(() -> quote.revise(offer(line(1, 2000)))).as("only a draft is edited")
                .extracting(QuoteTest::code).isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);
        quote.reopen();
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(quote.revision()).isEqualTo(2);
        assertThat(quote.customerFeedback()).isNull();
        quote.revise(offer(line(1, 2000)));
        quote.send(sales, NOW.plusSeconds(10));
        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void aCancellationSaysWhyAndOnlyAnAcceptedQuoteConvertsOnce() {
        Quote draft = draft();
        assertThatThrownBy(() -> draft.cancel(" ")).isInstanceOf(BusinessException.class);
        draft.cancel("Customer went elsewhere");
        assertThat(draft.status()).isEqualTo(QuoteStatus.CANCELLED);

        Quote typo = sent();
        typo.cancel("250 typed for 2,500: a corrected quote follows");
        assertThat(typo.status()).as("withdrawn before the customer answered").isEqualTo(QuoteStatus.CANCELLED);
        assertThat(typo.seenByCustomer()).as("the customer saw it, and sees it withdrawn").isTrue();
        assertThatThrownBy(() -> typo.accept(Map.of(), customerUser, NOW)).extracting(QuoteTest::code)
                .isEqualTo(ErrorCode.INVALID_QUOTE_TRANSITION);

        Quote quote = sent();
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
    void theCustomerOnlySeesQuotesOnceSent() {
        assertThat(QuoteStatus.DRAFT.seenByCustomer()).isFalse();
        assertThat(QuoteStatus.CANCELLED.seenByCustomer()).isFalse();
        assertThat(EnumSet.complementOf(EnumSet.of(QuoteStatus.DRAFT, QuoteStatus.CANCELLED)))
                .allMatch(QuoteStatus::seenByCustomer);
    }
}
