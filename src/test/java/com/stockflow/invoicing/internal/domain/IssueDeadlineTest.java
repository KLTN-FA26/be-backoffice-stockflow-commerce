package com.stockflow.invoicing.internal.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NĐ 254 Phụ lục điểm 7: signed and sent no later than the working day after the invoice's date. The deadline is the end
 * of that day in Vietnam (UTC+7), so 17:00Z the day before.
 */
class IssueDeadlineTest {

    @ParameterizedTest(name = "dated {0} -> due before {1}")
    @CsvSource({
            // Monday -> the end of Tuesday
            "2026-11-09, 2026-11-10T17:00:00Z",
            // Thursday -> the end of Friday
            "2026-11-12, 2026-11-13T17:00:00Z",
            // Friday, Saturday and Sunday -> the end of Monday
            "2026-11-13, 2026-11-16T17:00:00Z",
            "2026-11-14, 2026-11-16T17:00:00Z",
            "2026-11-15, 2026-11-16T17:00:00Z",
            // over the year's end: Thursday 31/12 -> the end of Friday 01/01 (holidays are not known: the safe side)
            "2026-12-31, 2027-01-01T17:00:00Z"})
    void theEndOfTheNextWorkingDay(LocalDate issueDate, Instant due) {
        assertThat(IssueDeadline.of(issueDate)).isEqualTo(due);
    }
}
