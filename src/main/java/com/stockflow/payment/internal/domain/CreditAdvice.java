package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What the review screen suggests to the accountant on a credit request (plan §4.4, owner decision 2026-11-26 §5
 * question 4): warnings only, never a refusal - the system does not decide credit, the accountant does.
 *
 * <ul>
 *   <li>{@link Warning#SHORT_HISTORY}: fewer contracts settled than {@code minSettledContracts} - too little history to
 *       go on;</li>
 *   <li>{@link Warning#ABOVE_SUGGESTED}: the limit asked is above the suggestion, {@code suggestedMonths} months of what
 *       the customer paid a month on average over the last twelve;</li>
 *   <li>{@link Warning#OVERDUE}: something is overdue now.</li>
 * </ul>
 *
 * @param suggestedLimit what the customer paid in the last twelve months, divided by twelve, times
 *                       {@code suggestedMonths}, in whole dong; zero for a customer who paid nothing
 */
public record CreditAdvice(BigDecimal suggestedLimit, List<Warning> warnings) {

    public enum Warning {
        SHORT_HISTORY,
        ABOVE_SUGGESTED,
        OVERDUE
    }

    public CreditAdvice {
        warnings = List.copyOf(warnings);
    }

    /**
     * @param requestedLimit      the limit asked
     * @param hasOverdue          whether an installment of the customer is overdue now
     * @param minSettledContracts {@code stockflow.payment.credit.min-settled-contracts}
     * @param suggestedMonths     {@code stockflow.payment.credit.suggested-months}
     */
    public static CreditAdvice of(PaymentHistory history, BigDecimal requestedLimit, boolean hasOverdue,
                                  int minSettledContracts, int suggestedMonths) {
        BigDecimal suggested = history.paidLast12Months().multiply(BigDecimal.valueOf(suggestedMonths))
                .divide(BigDecimal.valueOf(12), 0, RoundingMode.DOWN);
        List<Warning> warnings = new ArrayList<>();
        if (history.settledContracts() < minSettledContracts) {
            warnings.add(Warning.SHORT_HISTORY);
        }
        if (requestedLimit.compareTo(suggested) > 0) {
            warnings.add(Warning.ABOVE_SUGGESTED);
        }
        if (hasOverdue) {
            warnings.add(Warning.OVERDUE);
        }
        return new CreditAdvice(suggested, warnings);
    }
}
