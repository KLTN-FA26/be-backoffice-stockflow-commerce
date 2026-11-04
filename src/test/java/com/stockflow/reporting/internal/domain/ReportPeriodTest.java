package com.stockflow.reporting.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Test
    @DisplayName("nothing given: the last 30 days ending today")
    void defaults() {
        ReportPeriod period = ReportPeriod.of(null, null, TODAY);

        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(period.days()).isEqualTo(30);
    }

    @Test
    @DisplayName("only 'to' given: 30 days ending there")
    void onlyTo() {
        assertThat(ReportPeriod.of(null, LocalDate.of(2026, 3, 31), TODAY).from()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("366 days is the longest allowed; 367 is a 400")
    void maxRange() {
        assertThat(new ReportPeriod(TODAY.minusDays(365), TODAY).days()).isEqualTo(366);

        assertThatThrownBy(() -> new ReportPeriod(TODAY.minusDays(366), TODAY))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting("code").containsExactly("RangeTooLong");
                });
    }

    @Test
    @DisplayName("a start after the end is a 400")
    void inverted() {
        assertThatThrownBy(() -> new ReportPeriod(TODAY, TODAY.minusDays(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors()).extracting("code").containsExactly("RangeInverted"));
    }

    @Test
    @DisplayName("the previous period is the same number of days right before")
    void previous() {
        ReportPeriod previous = new ReportPeriod(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 24)).previous();

        assertThat(previous.from()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(previous.to()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(previous.days()).isEqualTo(15);
    }
}
