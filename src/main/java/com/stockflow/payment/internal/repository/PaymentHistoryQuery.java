package com.stockflow.payment.internal.repository;

import com.stockflow.payment.internal.domain.PaymentHistories;
import com.stockflow.payment.internal.domain.PaymentHistory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PaymentHistories} in one statement over the payment module's own tables (plan §4.4): the installments of the
 * customer's signed contracts and the live receipt lines that paid them.
 *
 * <ul>
 *   <li>A contract counts as settled when none of its installments still owes - each PAID, or CANCELLED (taken off by a
 *       closure, or not owed after a cancellation) - and at least one was paid: a contract of cancelled items alone
 *       taught nothing about how the customer pays.</li>
 *   <li>An installment counts for punctuality when it has a due day and is PAID; the day it was paid is the latest
 *       {@code received_on} of the live receipts paying it - the money that finished it - against its {@code due_date}.
 *       One paid before delivery has no due day ({@code ck_installment_due_date}) and is left out: nothing to be late
 *       against.</li>
 *   <li>The last twelve months are the receipts received after the same day a year before {@code today}, up to it.</li>
 * </ul>
 */
@Component
class PaymentHistoryQuery implements PaymentHistories {

    private final NamedParameterJdbcTemplate jdbc;

    PaymentHistoryQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PaymentHistory of(UUID customerId, LocalDate today) {
        Objects.requireNonNull(today, "today");
        var params = new MapSqlParameterSource("customer", customerId).addValue("today", today);
        return jdbc.queryForObject("""
                WITH paid AS (
                    SELECT i.due_date,
                           (SELECT MAX(r.received_on)
                              FROM payment.payment_receipt_line l
                              JOIN payment.payment_receipt r ON r.id = l.receipt_id
                             WHERE l.installment_id = i.id AND r.status = 'RECORDED') AS paid_on
                      FROM payment.installment i
                     WHERE i.customer_id = :customer AND i.status = 'PAID' AND i.due_date IS NOT NULL
                )
                SELECT (SELECT COUNT(*) FROM (SELECT contract_id FROM payment.installment
                                               WHERE customer_id = :customer
                                               GROUP BY contract_id
                                              HAVING BOOL_AND(status IN ('PAID', 'CANCELLED'))
                                                 AND BOOL_OR(status = 'PAID')) settled) AS settled_contracts,
                       (SELECT COUNT(*) FROM paid WHERE paid_on IS NOT NULL) AS paid_installments,
                       (SELECT COUNT(*) FROM paid WHERE paid_on <= due_date) AS on_time_installments,
                       (SELECT ROUND(AVG(paid_on - due_date)::numeric, 1) FROM paid WHERE paid_on > due_date)
                           AS average_days_late,
                       (SELECT COALESCE(SUM(amount), 0) FROM payment.payment_receipt
                         WHERE customer_id = :customer AND status = 'RECORDED'
                           AND received_on > (CAST(:today AS date) - INTERVAL '12 months')::date
                           AND received_on <= CAST(:today AS date)) AS paid_last_12_months
                """, params, (r, n) -> new PaymentHistory(r.getInt("settled_contracts"), r.getInt("paid_installments"),
                r.getInt("on_time_installments"), r.getBigDecimal("average_days_late"),
                r.getBigDecimal("paid_last_12_months").setScale(0, RoundingMode.DOWN)));
    }
}
