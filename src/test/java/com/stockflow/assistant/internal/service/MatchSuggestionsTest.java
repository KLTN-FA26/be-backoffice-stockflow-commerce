package com.stockflow.assistant.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.api.PayableInstallment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The match suggestions (work features 2.1): one read per kind of fact, only for a transfer waiting for review. */
class MatchSuggestionsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private static final UUID ABC = UUID.randomUUID();

    private final BankTransferReview review = mock(BankTransferReview.class);
    private final CustomerSummaries customers = mock(CustomerSummaries.class);
    private final MatchSuggestions suggestions = new MatchSuggestions(review, customers, CLOCK);

    private static BankTransferReview.Transfer transfer(UUID id, String status, String content) {
        return new BankTransferReview.Transfer(id, status, BigDecimal.valueOf(25_000_000),
                Instant.parse("2026-11-27T02:00:00Z"), content, "NEEDS_REVIEW".equals(status)
                ? "NO_INSTALLMENT_NAMED" : null, null);
    }

    private static PayableInstallment installment(String order, long outstanding) {
        String contract = "HD-" + order.substring(3) + "-1";
        return new PayableInstallment(UUID.randomUUID(), ABC, UUID.randomUUID(), contract, UUID.randomUUID(), order, 1,
                "Đợt 1", "ON_SIGNING", false, BigDecimal.valueOf(outstanding), BigDecimal.valueOf(outstanding), "VND",
                LocalDate.of(2026, 11, 30), "DUE", false, 0, contract + " DOT1");
    }

    @Test
    @DisplayName("the candidates and their customers are each read once, the customer's name comes back with the "
            + "suggestion and its tax code is only compared")
    void suggests() {
        UUID id = UUID.randomUUID();
        PayableInstallment ours = installment("SO-20261120-000321", 25_000_000);
        PayableInstallment other = installment("SO-20261101-000001", 99_000_000);
        when(review.transfer(id)).thenReturn(Optional.of(transfer(id, "NEEDS_REVIEW",
                "CONG TY ABC TT DON SO 20261120 000321")));
        when(review.candidates(id, MatchSuggestions.CANDIDATES)).thenReturn(List.of(
                new BankTransferReview.Candidate(other, false),
                new BankTransferReview.Candidate(ours, false)));
        when(customers.summariesOf(any())).thenReturn(Map.of(ABC, new CustomerSummary(ABC, null, "Công ty TNHH ABC",
                "ke-toan@abc.example.vn", "0903000000", "0312345678", null, "ACTIVE", 1, Instant.now())));

        MatchSuggestions.Result result = suggestions.suggest(id);

        assertThat(result.transfer().id()).isEqualTo(id);
        assertThat(result.ambiguous()).as("one clear best").isFalse();
        assertThat(result.suggestions()).first().satisfies(s -> {
            assertThat(s.installment().id()).isEqualTo(ours.id());
            assertThat(s.customerName()).isEqualTo("Công ty TNHH ABC");
            assertThat(s.reasons()).containsExactly("ORDER_NUMBER_IN_CONTENT", "CUSTOMER_NAME_IN_CONTENT",
                    "AMOUNT_EXACT", "DUE_SOON");
            assertThat(s.score()).isEqualTo(100);
        });
        verify(customers).summariesOf(java.util.Set.of(ABC));
    }

    @Test
    @DisplayName("a transfer applied or ignored already gets no suggestion, and no installment is read")
    void decidedAlready() {
        UUID id = UUID.randomUUID();
        when(review.transfer(id)).thenReturn(Optional.of(transfer(id, "APPLIED", "HD-20261120-000321-1 DOT1")));

        MatchSuggestions.Result result = suggestions.suggest(id);

        assertThat(result.suggestions()).isEmpty();
        verify(review).transfer(id);
        org.mockito.Mockito.verify(review, org.mockito.Mockito.never()).candidates(any(), anyInt());
        verifyNoInteractions(customers);
    }

    @Test
    @DisplayName("an unknown transfer is 404 BANK_TRANSACTION_NOT_FOUND")
    void unknown() {
        UUID id = UUID.randomUUID();
        when(review.transfer(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> suggestions.suggest(id)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.BANK_TRANSACTION_NOT_FOUND));
    }
}
