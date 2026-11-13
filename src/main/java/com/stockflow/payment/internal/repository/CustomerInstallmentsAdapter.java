package com.stockflow.payment.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.payment.api.CustomerInstallments;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payment module's answer to {@link CustomerInstallments}: one statement over its own instalments. Overdue is worked
 * out here from the due date and today's business date, as everywhere since V20261121000100: it is never stored.
 */
@Component
class CustomerInstallmentsAdapter implements CustomerInstallments {

    private static final int MAX_LIMIT = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    CustomerInstallmentsAdapter(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OpenInstallment> openOf(UUID customerId, int limit) {
        if (customerId == null) {
            return List.of();
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        return jdbc.query("""
                SELECT i.id, i.contract_id, i.contract_number, i.order_id, i.order_number, i.seq, i.name, i.amount,
                       i.paid_amount, i.currency, i.due_date, i.status
                  FROM payment.installment i
                 WHERE i.customer_id = :customer AND i.status IN ('NOT_DUE', 'DUE', 'PARTIALLY_PAID')
                 ORDER BY i.due_date ASC NULLS LAST, i.contract_number, i.seq
                 LIMIT :limit""", Map.of("customer", customerId, "limit", Math.max(1, Math.min(limit, MAX_LIMIT))),
                (rs, n) -> {
                    LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
                    return new OpenInstallment(rs.getObject("id", UUID.class), rs.getObject("contract_id", UUID.class),
                            rs.getString("contract_number"), rs.getObject("order_id", UUID.class),
                            rs.getString("order_number"), rs.getInt("seq"), rs.getString("name"),
                            rs.getBigDecimal("amount"), rs.getBigDecimal("paid_amount"), rs.getString("currency"),
                            dueDate, rs.getString("status"), dueDate != null && dueDate.isBefore(today));
                });
    }
}
