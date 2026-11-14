package com.stockflow.payment.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Receivables per customer, aged by due date (kltn-docs 15 §3 "công nợ phải thu", §4.3 step 8; 2026-11-14 plan, BE2-B):
 * what the reporting module lays out for the accountant and Sales, one row a customer.
 *
 * <p><b>Today only.</b> The figures are the installments as they stand - what each still owes now - aged against
 * {@code today}. There is no ledger of payments (15 §0: the books of money are the accounting software's, BR-06), so
 * nothing here can say what was owed on an earlier day: passing another day ages today's balances against it, which is
 * not "receivables as of that day". Callers pass the business day of today.</p>
 *
 * <p>Its own interface rather than more of {@link PaymentService}, which the order module calls inside its
 * transactions: a report needs this one read and nothing else.</p>
 */
public interface Receivables {

    /**
     * One row per customer (and currency) that owes anything after delivery - receivable or committed - as of
     * {@code today}, read in one statement over the payment module's installments. A customer that owes nothing is
     * absent.
     *
     * @param customerIdOrNull one customer, or null for every customer
     */
    List<CustomerReceivable> receivables(LocalDate today, UUID customerIdOrNull);
}
