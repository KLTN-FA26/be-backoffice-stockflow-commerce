package com.stockflow.procurement.internal.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
     * The highest limit among {@code roleIds} in force on {@code on} in {@code currency}; empty when none of the
     * roles has a row, or {@code roleIds} is empty. A user holding several roles gets the most generous of them.
     */
    public Optional<BigDecimal> highestLimit(Collection<UUID> roleIds, String currency, LocalDate on) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = String.join(",", Collections.nCopies(roleIds.size(), "?"));
        List<Object> args = new ArrayList<>(roleIds);
        args.add(currency);
        args.add(on);
        args.add(on);
        return Optional.ofNullable(jdbc.queryForObject("""
                SELECT MAX(max_po_amount) FROM procurement.approval_limits
                 WHERE role_id IN (%s) AND currency = ?
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)""".formatted(placeholders),
                BigDecimal.class, args.toArray()));
    }
}
