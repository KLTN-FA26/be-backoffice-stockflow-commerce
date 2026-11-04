package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The customers who owe past their due dates, for other modules (the assistant, ADR-0010): the collection list an
 * accountant works from.
 *
 * <p><b>Overdue as the rest of the system counts it.</b> A receivable the customer pays itself whose status is
 * OVERDUE: the nightly job marks it the day after its due date, and it stays so until it is paid in full. The same rows
 * {@link PaymentService#creditPosition} reads, so a customer listed here is one whose next credit order goes on hold
 * (BR-PAY-008). A balance a carrier collects on delivery is not the customer's debt, and is not in it.</p>
 *
 * <p>Call it inside the guard of {@code GET /api/v1/receivables},
 * {@code @RequiresPermission(resource = "payment-receivables", action = READ)}, for the back office only: a portal
 * customer holds that permission for its own receivables and must never see this list.</p>
 */
public interface OverdueReceivables {

    /** The most customers one call lists. */
    int MAX_CUSTOMERS = 100;

    /**
     * @param oldestFirst the oldest due date first; otherwise the largest amount first
     * @param limit       1..{@value #MAX_CUSTOMERS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a limit out of range
     */
    OverdueCustomers overdueCustomers(boolean oldestFirst, int limit);

    /**
     * @param customers        one row per customer and currency, at most {@code limit}
     * @param total            how many customers owe past due in all
     * @param rows             how many rows there are without the limit: more than {@code total} only when a customer
     *                         owes in two currencies
     * @param amountByCurrency what all of them owe past due, by currency
     */
    record OverdueCustomers(List<OverdueCustomer> customers, long total, long rows,
                            Map<String, BigDecimal> amountByCurrency) {
    }
}
