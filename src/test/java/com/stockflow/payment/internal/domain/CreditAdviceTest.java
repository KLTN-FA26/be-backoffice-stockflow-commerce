package com.stockflow.payment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The review's warnings (plan §4.4, owner decision 2026-11-26 §5 q4): warnings only, never a refusal. */
class CreditAdviceTest {

    private static PaymentHistory history(int settled, String paid12) {
        return new PaymentHistory(settled, 4, 3, null, new BigDecimal(paid12));
    }

    @Test
    @DisplayName("suggested: a month of the last twelve months' payments, times suggested-months, in whole dong")
    void suggested() {
        CreditAdvice advice = CreditAdvice.of(history(2, "1200000001"), new BigDecimal("100000000"), false, 2, 1);
        assertThat(advice.suggestedLimit()).isEqualByComparingTo("100000000");
        assertThat(advice.warnings()).isEmpty();
        assertThat(CreditAdvice.of(history(2, "1200000000"), BigDecimal.ONE, false, 2, 3).suggestedLimit())
                .isEqualByComparingTo("300000000");
    }

    @Test
    @DisplayName("too little history, more than suggested, something overdue: each a warning")
    void warnings() {
        CreditAdvice advice = CreditAdvice.of(history(1, "0"), new BigDecimal("1"), true, 2, 1);
        assertThat(advice.suggestedLimit()).isEqualByComparingTo("0");
        assertThat(advice.warnings()).containsExactly(CreditAdvice.Warning.SHORT_HISTORY,
                CreditAdvice.Warning.ABOVE_SUGGESTED, CreditAdvice.Warning.OVERDUE);
    }

    @Test
    @DisplayName("the on-time ratio is on-time over paid; none paid, none")
    void ratio() {
        assertThat(history(0, "0").onTimeRatio()).isEqualByComparingTo("0.75");
        assertThat(new PaymentHistory(0, 0, 0, null, BigDecimal.ZERO).onTimeRatio()).isNull();
    }
}
