package com.stockflow.invoicing.internal.domain;

import com.stockflow.common.domain.BusinessCalendar;

import java.time.Instant;
import java.time.LocalDate;

/**
 * By when an invoice must be issued: by the end of its own date, Vietnam time (WF-05 decision Q-1; NĐ 254/2026/NĐ-CP
 * Điều 9 khoản 1 - the invoice is made out when the goods change hands).
 *
 * <p><b>No next working day.</b> The working day after the invoice's date that NĐ 254/2026 Điều 9 khoản 5 and Phụ lục
 * điểm 7 leave for signing is for sales made at night by a seller without software that issues invoices
 * automatically - not a general grace period (owner, 23/11/2026). This system issues automatically as the delivery is
 * recorded, so the grace does not apply to it, under any provider: an original is due by the end of its delivery day,
 * a correction by the end of the day it is dated (the day it is issued). It used to be the end of the next working day,
 * which put the warning a day late and the "overdue" mark a working day after the breach.</p>
 */
public final class IssueDeadline {

    private IssueDeadline() {
    }

    /** The instant {@code issueDate} ends in Vietnam: issued before it is in time. */
    public static Instant of(LocalDate issueDate) {
        return issueDate.plusDays(1).atStartOfDay(BusinessCalendar.ZONE).toInstant();
    }
}
