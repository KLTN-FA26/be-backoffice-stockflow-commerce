package com.stockflow.assistant.internal.service;

import com.stockflow.payment.api.PayableInstallment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Which installment to chase first (2026-11-27 work features 2.2), as a number anyone can recompute from the facts it
 * shows. Pure: whether an installment is overdue and by how much is the payment module's; what the customer owes in
 * all and its credit limit come from the receivables and the credit terms the credit check itself reads.
 *
 * <p><b>Points</b>, added:</p>
 * <ul>
 *   <li>days overdue - 1 to 7: 20; 8 to 30: 30; 31 to 60: 40; more: 50. Due within the week, not yet overdue: 0. The
 *       longer money is late, the less likely it comes without a call, and an overdue installment blocks the
 *       customer's next contract (15 BR-10, BR-11);</li>
 *   <li>what it still owes - {@value #LARGE} VND or more: 20; {@value #MEDIUM} or more: 10;</li>
 *   <li>the customer owes more overdue money on other installments: 15 - one call settles several;</li>
 *   <li>the customer owes more than its credit limit after delivery: 15 - the exposure accounting approved is already
 *       spent.</li>
 * </ul>
 * <p>{@code HIGH} from {@value #HIGH_FROM} points, {@code MEDIUM} from {@value #MEDIUM_FROM}, else {@code LOW}: forty
 * days late is high whatever the amount; a large installment due next week is low until it is late.</p>
 */
final class CollectionPriority {

    static final long LARGE = 200_000_000L;
    static final long MEDIUM = 50_000_000L;
    static final int HIGH_FROM = 50;
    static final int MEDIUM_FROM = 25;

    enum Level { HIGH, MEDIUM, LOW }

    /** A fact that raised the priority, for the list to say in words. */
    enum Why { OVERDUE, DUE_SOON, LARGE_AMOUNT, OTHER_OVERDUE, OVER_CREDIT_LIMIT }

    /**
     * What the customer owes besides this installment, as the credit check reads it.
     *
     * @param otherOverdue what is overdue on its other installments; zero when nothing else is
     * @param exposure     what it owes after delivery, reached or not (receivable plus committed)
     * @param creditLimit  the limit accounting approved; null without one
     */
    record Customer(BigDecimal otherOverdue, BigDecimal exposure, BigDecimal creditLimit) {

        static final Customer UNKNOWN = new Customer(BigDecimal.ZERO, BigDecimal.ZERO, null);

        boolean overLimit() {
            return creditLimit != null && exposure != null && exposure.compareTo(creditLimit) > 0;
        }
    }

    /** The points, their level and why, the reasons in the order of {@link Why}. */
    record Rank(int points, Level level, List<Why> why) {

        Rank {
            why = List.copyOf(why);
        }
    }

    private CollectionPriority() {
    }

    static Rank of(PayableInstallment installment, Customer customer) {
        int points = 0;
        List<Why> why = new ArrayList<>();
        if (installment.overdue()) {
            int days = installment.daysOverdue();
            points += days > 60 ? 50 : days > 30 ? 40 : days > 7 ? 30 : 20;
            why.add(Why.OVERDUE);
        } else {
            why.add(Why.DUE_SOON);
        }
        if (installment.outstanding().compareTo(BigDecimal.valueOf(LARGE)) >= 0) {
            points += 20;
            why.add(Why.LARGE_AMOUNT);
        } else if (installment.outstanding().compareTo(BigDecimal.valueOf(MEDIUM)) >= 0) {
            points += 10;
            why.add(Why.LARGE_AMOUNT);
        }
        if (customer.otherOverdue() != null && customer.otherOverdue().signum() > 0) {
            points += 15;
            why.add(Why.OTHER_OVERDUE);
        }
        if (customer.overLimit()) {
            points += 15;
            why.add(Why.OVER_CREDIT_LIMIT);
        }
        Level level = points >= HIGH_FROM ? Level.HIGH : points >= MEDIUM_FROM ? Level.MEDIUM : Level.LOW;
        return new Rank(points, level, why);
    }
}
