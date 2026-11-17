package com.stockflow.invoicing.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.api.EInvoiceListing;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link EInvoiceListing} over {@code invoicing.einvoice}, in one statement and its count, ordered as the accountant's
 * list orders it ({@code EInvoiceQueriesAdapter.list}): what still has to be issued first, the nearest deadline first,
 * then the newest. Kept apart from that port because its filters are another module's - an order number rather than an
 * id, several statuses at once - and the screen's {@code Filter} is the controller's to shape.
 *
 * <p>Every value a caller gives is bound, never concatenated; the only text built here is the fixed predicates.</p>
 */
@Component
class EInvoiceListingAdapter implements EInvoiceListing {

    /** {@code EInvoiceStatus} not yet issued nor given up: what the deadline still applies to. */
    private static final String STILL_TO_ISSUE = "status IN ('DRAFT', 'REJECTED', 'SUBMITTING')";

    private final NamedParameterJdbcTemplate jdbc;

    EInvoiceListingAdapter(NamedParameterJdbcTemplate jdbc) {
        // The api's vocabulary is a copy of the domain's: a status added to one and not the other stops the start.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(EInvoiceStatus.values()).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("EInvoiceListing.STATUSES no longer matches EInvoiceStatus");
        }
        if (!Set.copyOf(TO_ISSUE_STATUSES).equals(Arrays.stream(EInvoiceStatus.values())
                .filter(status -> !status.wasIssued() && status != EInvoiceStatus.VOIDED_DRAFT).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("EInvoiceListing.TO_ISSUE_STATUSES no longer matches EInvoiceStatus");
        }
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<EInvoiceRow> einvoices(Filter filter, Instant now, int limit) {
        if (limit < 1 || limit > MAX_INVOICES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_INVOICES + ", not " + limit);
        }
        if (filter.issuedFrom() != null && filter.issuedTo() != null
                && filter.issuedTo().isBefore(filter.issuedFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "issuedTo is before issuedFrom");
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> predicates = new ArrayList<>();
        if (!filter.statuses().isEmpty()) {
            filter.statuses().forEach(EInvoiceListingAdapter::requireStatus);
            predicates.add("status IN (:statuses)");
            parameters.addValue("statuses", List.copyOf(filter.statuses()));
        }
        if (filter.orderNumber() != null && !filter.orderNumber().isBlank()) {
            predicates.add("upper(order_number) = :orderNumber");
            parameters.addValue("orderNumber", filter.orderNumber().strip().toUpperCase(Locale.ROOT));
        }
        if (filter.customerId() != null) {
            predicates.add("customer_id = :customer");
            parameters.addValue("customer", filter.customerId());
        }
        if (filter.issuedFrom() != null) {
            predicates.add("issue_date >= :issuedFrom");
            parameters.addValue("issuedFrom", java.sql.Date.valueOf(filter.issuedFrom()));
        }
        if (filter.issuedTo() != null) {
            predicates.add("issue_date <= :issuedTo");
            parameters.addValue("issuedTo", java.sql.Date.valueOf(filter.issuedTo()));
        }
        if (filter.overdueAt() != null) {
            // The screen's dueBefore and its overdue flag: reached means not before the deadline.
            predicates.add(STILL_TO_ISSUE + " AND issue_due_at <= :overdueAt");
            parameters.addValue("overdueAt", Timestamp.from(filter.overdueAt()));
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        Long total = jdbc.queryForObject("SELECT count(*) FROM invoicing.einvoice" + where, parameters, Long.class);
        parameters.addValue("limit", limit);
        // The text block's common indentation is stripped, so the space before ORDER BY is spelled out.
        List<EInvoiceRow> rows = jdbc.query("""
                SELECT code, type, status, adjusted, order_number, shipment_code, contract_number, customer_id,
                       buyer_name, issue_date, issue_due_at, template_code, symbol, invoice_number, currency, subtotal,
                       tax_total, grand_total, rejection_code, issued_at
                  FROM invoicing.einvoice""" + where + " " + """
                 ORDER BY CASE WHEN status IN ('DRAFT', 'REJECTED', 'SUBMITTING') THEN 0 ELSE 1 END,
                          CASE WHEN status IN ('DRAFT', 'REJECTED', 'SUBMITTING') THEN issue_due_at END,
                          created_at DESC, id
                 LIMIT :limit""", parameters, (rs, n) -> {
            String status = rs.getString("status");
            Instant dueAt = rs.getTimestamp("issue_due_at").toInstant();
            Timestamp issuedAt = rs.getTimestamp("issued_at");
            return new EInvoiceRow(rs.getString("code"), rs.getString("type"), status, rs.getBoolean("adjusted"),
                    rs.getString("order_number"), rs.getString("shipment_code"), rs.getString("contract_number"),
                    rs.getObject("customer_id", UUID.class), rs.getString("buyer_name"),
                    rs.getObject("issue_date", LocalDate.class), dueAt,
                    TO_ISSUE_STATUSES.contains(status) && now != null && !now.isBefore(dueAt),
                    rs.getString("template_code"), rs.getString("symbol"),
                    rs.getObject("invoice_number", Integer.class), rs.getString("currency"),
                    rs.getBigDecimal("subtotal"), rs.getBigDecimal("tax_total"), rs.getBigDecimal("grand_total"),
                    rs.getString("rejection_code"), issuedAt == null ? null : issuedAt.toInstant());
        });
        return PageResponse.of(rows, 0, limit, total == null ? 0 : total);
    }

    private static void requireStatus(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An e-invoice status is one of " + STATUSES + ", not " + name);
        }
    }
}
