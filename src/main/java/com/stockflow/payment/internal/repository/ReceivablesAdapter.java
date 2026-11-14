package com.stockflow.payment.internal.repository;

import com.stockflow.payment.api.CustomerReceivable;
import com.stockflow.payment.api.Receivables;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link Receivables}: one {@code GROUP BY customer_id} over {@code payment.installment}, the payment module's own table
 * ({@code ix_installment_customer} serves the one-customer reading).
 *
 * <p>Which installments, as kltn-docs 15 words it and {@code InstallmentBookAdapter.afterDeliveryUnpaid} counts for the
 * credit check: those paid after delivery and the fee items (a cancellation's, a failed trip's), not PAID and not
 * CANCELLED. What each still owes is {@code amount - paid_amount - reduced_amount} (a closure or a return lowers what is
 * owed, 4B-9, 4D-3). An installment after delivery is <i>receivable</i> once its milestone was reached - its goods
 * delivered, so it has a due day (ck_installment_due_date) - and <i>committed</i> until then; a fee item is receivable
 * from the day it is charged (15 §5.1: "đến hạn ngay"). The two together are exactly what
 * {@code afterDeliveryUnpaid} sums, so the report and the credit check never disagree on a customer's exposure.</p>
 *
 * <p>Ages are whole days from the due day to {@code today}, both business days (the caller's {@code today} is the
 * Asia/Ho_Chi_Minh date): due today or later is not yet due, the same test as {@code Installment.overdue} and
 * {@code payment.installment_is_overdue} ("past its due day and still owing"). A receivable item with no due day,
 * which the CHECKs do not allow, would count as not yet due rather than vanish from the buckets.</p>
 */
@Component
class ReceivablesAdapter implements Receivables {

    private final NamedParameterJdbcTemplate jdbc;

    ReceivablesAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerReceivable> receivables(LocalDate today, UUID customerIdOrNull) {
        Objects.requireNonNull(today, "today");
        MapSqlParameterSource params = new MapSqlParameterSource("today", today);
        String customer = "";
        if (customerIdOrNull != null) {
            customer = "AND customer_id = :customer";
            params.addValue("customer", customerIdOrNull);
        }
        return jdbc.query("""
                SELECT customer_id, currency,
                       COALESCE(SUM(owed) FILTER (WHERE receivable), 0) AS receivable,
                       COALESCE(SUM(owed) FILTER (WHERE receivable AND (age IS NULL OR age <= 0)), 0) AS not_yet_due,
                       COALESCE(SUM(owed) FILTER (WHERE receivable AND age BETWEEN 1 AND 30), 0) AS overdue_1_30,
                       COALESCE(SUM(owed) FILTER (WHERE receivable AND age BETWEEN 31 AND 60), 0) AS overdue_31_60,
                       COALESCE(SUM(owed) FILTER (WHERE receivable AND age BETWEEN 61 AND 90), 0) AS overdue_61_90,
                       COALESCE(SUM(owed) FILTER (WHERE receivable AND age > 90), 0) AS overdue_over_90,
                       COALESCE(SUM(owed) FILTER (WHERE NOT receivable), 0) AS committed_not_reached,
                       MIN(due_date) FILTER (WHERE receivable) AS oldest_due_date,
                       COUNT(*) FILTER (WHERE receivable) AS open_items
                  FROM (SELECT customer_id, currency, due_date,
                               amount - paid_amount - reduced_amount AS owed,
                               milestone <> 'AFTER_DELIVERY' OR reached_at IS NOT NULL AS receivable,
                               CAST(:today AS date) - due_date AS age
                          FROM payment.installment
                         WHERE milestone IN ('AFTER_DELIVERY', 'CANCELLATION_FEE', 'FAILED_TRIP_FEE')
                           AND status NOT IN ('PAID', 'CANCELLED')
                           %s) owing
                 GROUP BY customer_id, currency
                 ORDER BY customer_id, currency""".formatted(customer), params, (rs, n) -> new CustomerReceivable(
                rs.getObject("customer_id", UUID.class), rs.getString("currency"), rs.getBigDecimal("receivable"),
                rs.getBigDecimal("not_yet_due"), rs.getBigDecimal("overdue_1_30"), rs.getBigDecimal("overdue_31_60"),
                rs.getBigDecimal("overdue_61_90"), rs.getBigDecimal("overdue_over_90"),
                rs.getBigDecimal("committed_not_reached"), rs.getObject("oldest_due_date", LocalDate.class),
                rs.getInt("open_items")));
    }
}
