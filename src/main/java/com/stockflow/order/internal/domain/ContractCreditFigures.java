package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a contract's plan asks of the customer's credit, against what they owe (kltn-docs 15 BR-10, BR-11): the credit
 * it asks for is what it pays after delivery; what the customer owes besides is what the installments of their signed
 * contracts paid after delivery still owe, and the part of it already overdue. Something overdue, or past the limit
 * with this contract, and the check fails.
 *
 * @param outcome       {@code WITHIN_LIMIT}, {@code OVER_LIMIT} or {@code OVERDUE}
 * @param creditLimit   the customer's limit; zero without one
 * @param exposure      what the customer owes after delivery on other signed contracts, not yet due included
 * @param afterDelivery what this contract pays after delivery; above zero (a plan that asks no credit is not checked)
 * @param overdueAmount what is unpaid past its due date; zero when nothing is overdue
 */
public record ContractCreditFigures(CreditChecks.Outcome outcome, BigDecimal creditLimit, BigDecimal exposure,
                                    BigDecimal afterDelivery, BigDecimal overdueAmount) {

    public ContractCreditFigures {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(creditLimit, "creditLimit");
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(afterDelivery, "afterDelivery");
        overdueAmount = overdueAmount == null ? BigDecimal.ZERO : overdueAmount;
    }

    /** The check of these figures: overdue first, whatever the limit (15 BR-11), then the limit. */
    public static ContractCreditFigures check(BigDecimal creditLimit, BigDecimal exposure, BigDecimal afterDelivery,
                                              boolean hasOverdue, BigDecimal overdueAmount) {
        BigDecimal limit = creditLimit == null ? BigDecimal.ZERO : creditLimit;
        CreditChecks.Outcome outcome = hasOverdue ? CreditChecks.Outcome.OVERDUE
                : exposure.add(afterDelivery).compareTo(limit) > 0 ? CreditChecks.Outcome.OVER_LIMIT
                : CreditChecks.Outcome.WITHIN_LIMIT;
        return new ContractCreditFigures(outcome, limit, exposure, afterDelivery,
                hasOverdue ? overdueAmount : BigDecimal.ZERO);
    }

    /** Whether the check failed: accounting decides before the contract is sent (15 BR-11 as changed on 2026-11-26). */
    public boolean fails() {
        return outcome != CreditChecks.Outcome.WITHIN_LIMIT;
    }

    /**
     * Decision 2 of the credit plan (2026-11-26): a contract accounting approved before it was sent is checked again at
     * signing, and passes while it is no worse than what was approved - nothing overdue that was not then, no more
     * overdue than then, and what the customer owes with this contract no more than then. Worse, and the order is held
     * for accounting as any failed check at signing is.
     */
    public boolean noWorseThan(ContractCreditFigures approved) {
        Objects.requireNonNull(approved, "approved");
        if (overdueAmount.signum() > 0 && (approved.overdueAmount.signum() == 0
                || overdueAmount.compareTo(approved.overdueAmount) > 0)) {
            return false;
        }
        return exposure.add(afterDelivery).compareTo(approved.exposure.add(approved.afterDelivery)) <= 0;
    }
}
