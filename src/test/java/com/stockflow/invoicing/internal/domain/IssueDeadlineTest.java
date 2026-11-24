package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 Q-1 (owner, 23/11/2026): an invoice is due by the end of its own date - the next-working-day grace of NĐ
 * 254/2026 Điều 9 khoản 5 / Phụ lục điểm 7 is for night sales without automatic issuing, which this system is not. The
 * end of a day in Vietnam (UTC+7) is 17:00Z of that day; weekends and the year's end change nothing.
 */
class IssueDeadlineTest {

    @ParameterizedTest(name = "dated {0} -> due before {1}")
    @CsvSource({
            // Monday -> the end of Monday, not Tuesday
            "2026-11-09, 2026-11-09T17:00:00Z",
            // Friday -> the end of Friday, not Monday
            "2026-11-13, 2026-11-13T17:00:00Z",
            // Saturday and Sunday -> their own end: a delivery on a weekend is invoiced that day
            "2026-11-14, 2026-11-14T17:00:00Z",
            "2026-11-15, 2026-11-15T17:00:00Z",
            // 31/12 -> the end of 31/12 (the reduced rate's last day: issued that day, Q-1 §1.5 step 8)
            "2026-12-31, 2026-12-31T17:00:00Z"})
    void theEndOfItsOwnDay(LocalDate issueDate, Instant due) {
        assertThat(IssueDeadline.of(issueDate)).isEqualTo(due);
    }
}
