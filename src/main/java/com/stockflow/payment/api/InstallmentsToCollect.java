package com.stockflow.payment.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The installments someone should chase: past their due day and still owing, or falling due within the next days
 * (kltn-docs 15 §4.3; the assistant's collections list, 2026-11-27 work features 2.2). Read-only, one statement.
 *
 * <p>Overdue is the installment's flag ({@code payment.installment_is_overdue}), never a status; an installment
 * before delivery has no due day and never appears, as it never is overdue.</p>
 */
public interface InstallmentsToCollect {

    /**
     * @param today the business day the rows were read on, the one {@link PayableInstallment#daysOverdue} counts
     *              from
     * @param total how many installments there are, of which {@code items} are the first
     */
    record Page(LocalDate today, List<PayableInstallment> items, int total) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * Every installment still owing whose due day is no later than {@code withinDays} (0 to 31) after today: the most
     * overdue first (earliest due day), then the largest. At most {@code limit} (1 to 500).
     */
    Page dueWithin(int withinDays, int limit);
}
