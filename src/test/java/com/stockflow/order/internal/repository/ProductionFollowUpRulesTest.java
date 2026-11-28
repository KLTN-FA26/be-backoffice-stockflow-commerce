package com.stockflow.order.internal.repository;

import com.stockflow.order.api.ProductionOrderQueries.FollowUpReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Why a production order is on the coordinator's follow-up list: the delay alerts' rules on today's dates. */
class ProductionFollowUpRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    @DisplayName("past its need-by day is LATE, counted in days from it, whatever the producer said")
    void lateByNeedBy() {
        FollowUpReason reason = ProductionOrderQueriesAdapter.reasonOf("IN_PRODUCTION", TODAY.minusDays(4),
                TODAY.plusDays(2), TODAY, 3);

        assertThat(reason).isEqualTo(FollowUpReason.LATE);
        assertThat(ProductionOrderQueriesAdapter.daysLate(reason, TODAY.minusDays(4), TODAY.plusDays(2), TODAY))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("the producer's own day passed, the need-by day not yet: PRODUCER_OVERDUE, counted from the producer's")
    void producerOverdue() {
        FollowUpReason reason = ProductionOrderQueriesAdapter.reasonOf("ACCEPTED", TODAY.plusDays(5),
                TODAY.minusDays(1), TODAY, null);

        assertThat(reason).isEqualTo(FollowUpReason.PRODUCER_OVERDUE);
        assertThat(ProductionOrderQueriesAdapter.daysLate(reason, TODAY.plusDays(5), TODAY.minusDays(1), TODAY))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the producer's day after the need-by day is AT_RISK; due within the days asked is DUE_SOON, and "
            + "only when asked")
    void atRiskAndDueSoon() {
        assertThat(ProductionOrderQueriesAdapter.reasonOf("IN_PRODUCTION", TODAY.plusDays(5), TODAY.plusDays(8),
                TODAY, null)).isEqualTo(FollowUpReason.AT_RISK);
        assertThat(ProductionOrderQueriesAdapter.reasonOf("IN_PRODUCTION", TODAY.plusDays(10), TODAY.plusDays(3),
                TODAY, 3)).isEqualTo(FollowUpReason.DUE_SOON);
        assertThat(ProductionOrderQueriesAdapter.reasonOf("IN_PRODUCTION", TODAY.plusDays(10), TODAY.plusDays(3),
                TODAY, null)).isEqualTo(FollowUpReason.NONE);
        assertThat(ProductionOrderQueriesAdapter.reasonOf("SENT", TODAY.plusDays(2), null, TODAY, 3))
                .as("a SENT one has no producer's day yet: its need-by day counts").isEqualTo(FollowUpReason.DUE_SOON);
        assertThat(ProductionOrderQueriesAdapter.daysLate(FollowUpReason.AT_RISK, TODAY, TODAY, TODAY)).isNull();
    }

    @Test
    @DisplayName("one not with its producer is never on the list: a draft, one received in full, closed or cancelled")
    void notWithProducer() {
        for (String status : new String[] {"DRAFT", "PENDING_APPROVAL", "RECEIVED", "CLOSED", "CANCELLED"}) {
            assertThat(ProductionOrderQueriesAdapter.reasonOf(status, TODAY.minusDays(9), TODAY.minusDays(9), TODAY,
                    3)).as(status).isEqualTo(FollowUpReason.NONE);
        }
    }
}
