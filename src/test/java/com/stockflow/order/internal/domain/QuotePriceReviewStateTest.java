package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** kltn-docs 14 BR-10 (2026-11-26): a draft waiting for its prices to be approved, as the quote holds it. */
class QuotePriceReviewStateTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private final UUID sales = UUID.randomUUID();

    private static Quote.Offer offer(Instant validUntil) {
        QuoteLine line = QuoteLine.carton(UUID.randomUUID(), 1, new Sku("LY12-ACME-01"), UUID.randomUUID(), 50, 20,
                List.of(PriceTier.of(10, new BigDecimal("900"))), null, null, "STANDARD", BigDecimal.TEN);
        return new Quote.Offer(validUntil, null, List.of(line));
    }

    private static Quote draft() {
        return Quote.draft(UUID.randomUUID(), "QT-20261126-0001", UUID.randomUUID(), UUID.randomUUID(),
                offer(NOW.plusSeconds(86_400)));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void submittedItStaysADraftLockedAgainstEditsAndSends() {
        Quote quote = draft();
        quote.submitForPriceReview(sales, NOW);

        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(quote.priceReviewPending()).isTrue();
        assertThat(quote.seenByCustomer()).isFalse();
        assertThatThrownBy(() -> quote.revise(offer(NOW.plusSeconds(86_400))))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_PENDING));
        assertThatThrownBy(() -> quote.send(sales, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_PENDING));
        assertThatThrownBy(() -> quote.submitForPriceReview(sales, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_PENDING));
    }

    @Test
    void approvedItIsSentAsTheSalesAccountThatAsked() {
        Quote quote = draft();
        quote.submitForPriceReview(sales, NOW);

        quote.approvePrice(sales, NOW.plusSeconds(60));

        assertThat(quote.status()).isEqualTo(QuoteStatus.SENT);
        assertThat(quote.priceReviewPending()).isFalse();
        assertThat(quote.sentBy()).isEqualTo(sales);
        assertThat(quote.sentAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void anApprovalAfterTheValidityIsRefusedAndTheQuoteStillWaits() {
        Quote quote = draft();
        quote.submitForPriceReview(sales, NOW);

        assertThatThrownBy(() -> quote.approvePrice(sales, NOW.plusSeconds(2 * 86_400)))
                .isInstanceOf(BusinessException.class);
        assertThat(quote.status()).isEqualTo(QuoteStatus.DRAFT);
        assertThat(quote.priceReviewPending()).isTrue();
    }

    @Test
    void rejectedOrWithdrawnItIsSalesToEditAgain() {
        Quote rejected = draft();
        rejected.submitForPriceReview(sales, NOW);
        rejected.rejectPrice();
        rejected.revise(offer(NOW.plusSeconds(86_400)));

        Quote withdrawn = draft();
        withdrawn.submitForPriceReview(sales, NOW);
        withdrawn.withdrawPriceReview();
        withdrawn.send(sales, NOW);

        assertThat(rejected.priceReviewPending()).isFalse();
        assertThat(withdrawn.status()).isEqualTo(QuoteStatus.SENT);
    }

    @Test
    void nothingWaitingIsNothingToDecide() {
        Quote quote = draft();

        assertThatThrownBy(() -> quote.approvePrice(sales, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_NOT_PENDING));
        assertThatThrownBy(quote::rejectPrice)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_NOT_PENDING));
        assertThatThrownBy(quote::withdrawPriceReview)
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.QUOTE_PRICE_REVIEW_NOT_PENDING));
    }

    @Test
    void cancelledWhileItWaitsItWaitsNoMore() {
        Quote quote = draft();
        quote.submitForPriceReview(sales, NOW);

        quote.cancel("Khach khong mua");

        assertThat(quote.status()).isEqualTo(QuoteStatus.CANCELLED);
        assertThat(quote.priceReviewPending()).isFalse();
    }

    @Test
    void onlyADraftIsRestoredWaiting() {
        assertThatThrownBy(() -> Quote.restore(UUID.randomUUID(), "QT-1", UUID.randomUUID(), UUID.randomUUID(),
                QuoteStatus.SENT, 1, offer(NOW.plusSeconds(60)), NOW, sales, null, null, null, null, null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
