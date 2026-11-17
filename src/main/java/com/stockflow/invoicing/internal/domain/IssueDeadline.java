package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.domain.BusinessCalendar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

/**
 * By when an invoice must be signed and sent for the tax authority's code: "chậm nhất là ngày làm việc tiếp theo kể từ
 * thời điểm lập hóa đơn" (NĐ 254/2026/NĐ-CP Phụ lục điểm 7, as read in plans/E0-legal.md §2(e)5).
 *
 * <p>The invoice's date is the day the goods were delivered, not the day the accountant issues it, so a draft that
 * waits is running out of time from the moment it is drawn up. The deadline is the end of the next working day after
 * that date, Vietnam time.</p>
 *
 * <p>A working day here is Monday to Friday. Public holidays are not known to the system, so over a holiday this
 * deadline is earlier than the law's - the safe side: the accountant is warned too soon, never too late.</p>
 */
public final class IssueDeadline {

    private IssueDeadline() {
    }

    /** The instant the next working day after {@code issueDate} ends: issued before it is in time. */
    public static Instant of(LocalDate issueDate) {
        LocalDate day = issueDate.plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return day.plusDays(1).atStartOfDay(BusinessCalendar.ZONE).toInstant();
    }
}
