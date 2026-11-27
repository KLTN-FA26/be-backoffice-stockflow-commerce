package com.stockflow.order.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kltn-docs 15 BR-11 as changed on 2026-11-26: the credit check of a contract's plan, and decision 2 of the credit plan -
 * a contract approved before it was sent passes at signing while it is no worse than what was approved.
 */
class ContractCreditFiguresTest {

    private static BigDecimal v(long amount) {
        return BigDecimal.valueOf(amount);
    }

    @Test
    @DisplayName("overdue first whatever the limit, then the limit with this contract; within it the check passes")
    void theCheck() {
        assertThat(ContractCreditFigures.check(v(100), v(0), v(10), true, v(5)).outcome())
                .isEqualTo(CreditChecks.Outcome.OVERDUE);
        ContractCreditFigures over = ContractCreditFigures.check(v(100), v(60), v(41), false, v(0));
        assertThat(over.outcome()).isEqualTo(CreditChecks.Outcome.OVER_LIMIT);
        assertThat(over.fails()).isTrue();
        ContractCreditFigures within = ContractCreditFigures.check(v(100), v(60), v(40), false, v(0));
        assertThat(within.outcome()).isEqualTo(CreditChecks.Outcome.WITHIN_LIMIT);
        assertThat(within.fails()).isFalse();
        assertThat(ContractCreditFigures.check(null, v(0), v(1), false, null).creditLimit())
                .as("no limit reads as zero").isEqualByComparingTo("0");
        assertThat(ContractCreditFigures.check(v(100), v(0), v(1), false, v(7)).overdueAmount())
                .as("nothing overdue is zero overdue").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("decision 2: owed as much or less, nothing newly overdue - no worse than approved")
    void noWorse() {
        ContractCreditFigures approved = ContractCreditFigures.check(v(100), v(80), v(40), false, v(0));
        assertThat(ContractCreditFigures.check(v(100), v(80), v(40), false, v(0)).noWorseThan(approved)).isTrue();
        assertThat(ContractCreditFigures.check(v(100), v(50), v(40), false, v(0)).noWorseThan(approved))
                .as("a receipt recorded since").isTrue();
        assertThat(ContractCreditFigures.check(v(90), v(80), v(40), false, v(0)).noWorseThan(approved))
                .as("the amount approved, not the limit, is the line").isTrue();

        ContractCreditFigures overdue = ContractCreditFigures.check(v(1000), v(0), v(40), true, v(30));
        assertThat(ContractCreditFigures.check(v(1000), v(0), v(40), true, v(30)).noWorseThan(overdue)).isTrue();
        assertThat(ContractCreditFigures.check(v(1000), v(0), v(40), true, v(10)).noWorseThan(overdue))
                .as("part of the overdue paid since").isTrue();
    }

    @Test
    @DisplayName("decision 2: more owed, more overdue, or overdue where nothing was - worse, held at signing")
    void worse() {
        ContractCreditFigures approved = ContractCreditFigures.check(v(100), v(80), v(40), false, v(0));
        assertThat(ContractCreditFigures.check(v(100), v(81), v(40), false, v(0)).noWorseThan(approved))
                .as("another contract signed since").isFalse();
        assertThat(ContractCreditFigures.check(v(1000), v(0), v(40), true, v(1)).noWorseThan(approved))
                .as("something overdue that was not when approved").isFalse();

        ContractCreditFigures overdue = ContractCreditFigures.check(v(1000), v(0), v(40), true, v(30));
        assertThat(ContractCreditFigures.check(v(1000), v(0), v(40), true, v(31)).noWorseThan(overdue))
                .as("more overdue").isFalse();
    }
}
