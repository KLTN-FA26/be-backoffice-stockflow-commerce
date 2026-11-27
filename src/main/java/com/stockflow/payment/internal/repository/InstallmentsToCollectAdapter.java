package com.stockflow.payment.internal.repository;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.payment.api.InstallmentsToCollect;
import com.stockflow.payment.api.PayableInstallment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The payment module's answer to {@link InstallmentsToCollect}: one statement, its total counted in the same pass
 * ({@code count(*) OVER ()}), so the list and the number beside it never disagree.
 */
@Component
class InstallmentsToCollectAdapter implements InstallmentsToCollect {

    private static final int MAX_LIMIT = 500;
    private static final int MAX_DAYS = 31;

    private static final String DUE = """
            SELECT %s, count(*) OVER () AS total
              FROM payment.installment i
             WHERE %s AND i.due_date IS NOT NULL AND i.due_date <= :last
             ORDER BY i.due_date ASC, %s DESC, i.contract_number, i.seq
             LIMIT :limit""".formatted(PayableInstallmentRows.COLUMNS, PayableInstallmentRows.PAYABLE,
            PayableInstallmentRows.OUTSTANDING);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    InstallmentsToCollectAdapter(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Page dueWithin(int withinDays, int limit) {
        LocalDate today = BusinessCalendar.date(clock.instant());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("today", today)
                .addValue("last", today.plusDays(Math.max(0, Math.min(withinDays, MAX_DAYS))))
                .addValue("limit", Math.max(1, Math.min(limit, MAX_LIMIT)));
        List<PayableInstallment> items = new ArrayList<>();
        int[] total = {0};
        jdbc.query(DUE, parameters, rs -> {
            items.add(PayableInstallmentRows.read(rs, today));
            total[0] = rs.getInt("total");
        });
        return new Page(today, items, total[0]);
    }
}
