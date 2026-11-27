package com.stockflow.assistant.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerSummaries;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.payment.api.BankTransferReview;
import com.stockflow.payment.api.PayableInstallment;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where to look for a bank transfer the feed could not match (2026-11-27 work features 2.1): the Accountant, who
 * answers personally for every receipt recorded, opens a transfer waiting for review and sees at most three
 * installments it most likely pays, each with why - so the matching by hand starts from the likely contract rather
 * than from the whole installment list. It only points: the Accountant still assigns the transfer on its own screen,
 * through every rule of a receipt, and nothing here records anything.
 *
 * <p>Deterministic, no model: the candidates and their figures are the payment module's
 * ({@link BankTransferReview#candidates}, "payable" and "still owes" as the feed itself reads them), the customers'
 * names and tax codes are read once for all of them, and {@link TransferMatchScorer} weighs. A transfer not waiting
 * for review gets no suggestion: it was applied or ignored already, and a list beside it would read as a second
 * opinion on a decision that is made.</p>
 */
@Service
public class MatchSuggestions {

    /**
     * Candidates read: the likeliest first ({@link BankTransferReview#candidates}), so a business with more payable
     * installments than this loses only those matched by nothing but the payer's name or a part payment.
     */
    static final int CANDIDATES = 300;

    private static final String NEEDS_REVIEW = "NEEDS_REVIEW";

    private final BankTransferReview review;
    private final CustomerSummaries customers;
    private final Clock clock;

    MatchSuggestions(BankTransferReview review, CustomerSummaries customers, Clock clock) {
        this.review = review;
        this.customers = customers;
        this.clock = clock;
    }

    /**
     * What the panel shows: the transfer, then at most three installments, best first.
     *
     * @param ambiguous the best two score the same ({@link TransferMatchScorer#ambiguous}): "best first" is no
     *                  preference between them
     */
    public record Result(BankTransferReview.Transfer transfer, List<Suggestion> suggestions, boolean ambiguous) {

        public Result {
            suggestions = List.copyOf(suggestions);
        }
    }

    /**
     * @param customerName the installment's customer's registered name; null when it cannot be read
     * @param score        0 to 100 ({@link TransferMatchScorer})
     * @param reasons      the signals that scored it ({@link TransferMatchScorer.Reason} names)
     */
    public record Suggestion(PayableInstallment installment, String customerName, int score, List<String> reasons) {

        public Suggestion {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Its use is counted by the caller once this has answered, so a refusal or a failure is not a use.
     *
     * @throws BusinessException {@code BANK_TRANSACTION_NOT_FOUND}
     */
    public Result suggest(UUID transactionId) {
        BankTransferReview.Transfer transfer = review.transfer(transactionId).orElseThrow(() -> new BusinessException(
                ErrorCode.BANK_TRANSACTION_NOT_FOUND, "No bank transaction " + transactionId)
                .localized("refusal.payment.bankTransactionNotFound", transactionId));
        if (!NEEDS_REVIEW.equals(transfer.status())) {
            return new Result(transfer, List.of(), false);
        }
        List<BankTransferReview.Candidate> candidates = review.candidates(transactionId, CANDIDATES);
        Set<UUID> customerIds = new LinkedHashSet<>();
        candidates.forEach(candidate -> customerIds.add(candidate.installment().customerId()));
        Map<UUID, CustomerSummary> summaries = customerIds.isEmpty() ? Map.of() : customers.summariesOf(customerIds);
        Map<UUID, TransferMatchScorer.Payer> payers = new HashMap<>();
        summaries.forEach((id, summary) -> payers.put(id, new TransferMatchScorer.Payer(summary.fullName(),
                summary.taxCode())));
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<TransferMatchScorer.Suggestion> best = TransferMatchScorer.suggest(transfer.amount(), transfer.content(),
                candidates, payers, today);
        return new Result(transfer, best.stream().map(s -> {
            CustomerSummary summary = summaries.get(s.installment().customerId());
            return new Suggestion(s.installment(), summary == null ? null : summary.fullName(), s.score(),
                    s.reasons().stream().map(Enum::name).toList());
        }).toList(), TransferMatchScorer.ambiguous(best));
    }
}
