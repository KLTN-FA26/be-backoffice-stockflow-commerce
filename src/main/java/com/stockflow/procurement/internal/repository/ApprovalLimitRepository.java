package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.procurement.internal.domain.ApprovalLimit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads {@code procurement.approval_limits}: how much a role may approve (and so submit without anyone else
 * approving), per currency and period. Read only here - no screen or endpoint maintains the table yet; rows are put
 * there by the seed or by hand, and {@code ex_approval_limits_overlap} keeps one role's periods from overlapping.
 */
@Repository
public class ApprovalLimitRepository {

    private final JdbcTemplate jdbc;

    public ApprovalLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The highest limit among {@code roleIds} in force on {@code on} in {@code currency}, with the row it comes from;
     * empty when none of the roles has a row, or {@code roleIds} is empty. A user holding several roles gets the most
     * generous of them; of two equal ones the lower id, so the choice is stable.
     */
    public Optional<ApprovalLimit> highestLimit(Collection<UUID> roleIds, Currency currency, LocalDate on) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = String.join(",", Collections.nCopies(roleIds.size(), "?"));
        List<Object> args = new ArrayList<>(roleIds);
        args.add(currency.getCurrencyCode());
        args.add(on);
        args.add(on);
        return jdbc.query("""
                SELECT id, role_id, max_po_amount FROM procurement.approval_limits
                 WHERE role_id IN (%s) AND currency = ?
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY max_po_amount DESC, id LIMIT 1""".formatted(placeholders),
                (rs, row) -> new ApprovalLimit(rs.getObject("id", UUID.class), rs.getObject("role_id", UUID.class),
                        new Money(rs.getBigDecimal("max_po_amount"), currency)),
                args.toArray()).stream().findFirst();
    }
}
