package com.stockflow.order.internal.repository;

import com.stockflow.common.security.DataScopeContext;
import com.stockflow.common.security.ScopeViolationException;
import com.stockflow.order.internal.domain.SalesContractList;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.order.internal.domain.TermsReview;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SalesContractList} in SQL: the contract joined to its order once, so a page costs two statements (the rows and
 * their total) and the summary two, however many contracts there are. The buyer's name is the one written on the contract
 * when it was drawn up, and the Sales owner the one it recorded then (14 BR-26): the same table, no other schema is
 * joined. Everything user-given is a bound parameter; the order of the rows is fixed.
 *
 * <p><b>Data scope.</b> Both the page and the summary see only the contracts of the orders the caller's data scope
 * reaches, the same rows a single contract is read under ({@code OrderRepository#findByIdInScopeForReading}): this is
 * plain SQL, so the scope that {@code ScopedJpaRepository} applies to the order is applied here by hand, with the same
 * rules as {@link com.stockflow.common.security.DataScopeSpecifications#forScope} over the order (owner = the order's
 * customer, no warehouse, no team) and the same refusal when no scope is in force. Without it a caller limited to some
 * orders would list - and count - every customer's contract with its prices.</p>
 */
@Repository
class SalesContractListAdapter implements SalesContractList {

    private static final String FROM = """

              FROM ordering.sales_contract k
              JOIN ordering.customer_order o ON o.id = k.order_id""";

    private final NamedParameterJdbcTemplate jdbc;

    SalesContractListAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Page page(Query query) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("today", Date.valueOf(query.today()));
        String where = where(query, params) + inScope(params);
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, params, Long.class);
        if (total == null || total == 0) {
            return new Page(List.of(), 0);
        }
        params.addValue("limit", query.size()).addValue("offset", (long) query.page() * query.size());
        List<Row> rows = jdbc.query("""
                SELECT k.id, k.order_id, k.contract_number, o.order_number, o.customer_id, k.buyer_name, k.status,
                       k.revision, o.total_amount, k.sent_at, k.sign_by, k.customer_signed_at, k.signed_at,
                       k.terms_review, k.credit_review, k.sales_owner_id,
                       (k.status = 'SENT' AND k.sign_by IS NOT NULL AND k.sign_by < :today) AS overdue_to_sign"""
                + FROM + where + " ORDER BY k.created_at DESC, k.id DESC LIMIT :limit OFFSET :offset", params,
                (rs, n) -> new Row(rs.getObject("order_id", UUID.class), rs.getObject("id", UUID.class),
                        rs.getString("contract_number"), rs.getString("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getString("buyer_name"),
                        SalesContractStatus.valueOf(rs.getString("status")), rs.getInt("revision"),
                        rs.getBigDecimal("total_amount"), instant(rs.getTimestamp("sent_at")),
                        rs.getDate("sign_by") == null ? null : rs.getDate("sign_by").toLocalDate(),
                        rs.getBoolean("overdue_to_sign"), instant(rs.getTimestamp("customer_signed_at")),
                        instant(rs.getTimestamp("signed_at")), TermsReview.valueOf(rs.getString("terms_review")),
                        rs.getString("credit_review"), rs.getObject("sales_owner_id", UUID.class)));
        return new Page(rows, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(LocalDate today) {
        Map<SalesContractStatus, Long> byStatus = new EnumMap<>(SalesContractStatus.class);
        long[] overdue = new long[1];
        long[] pending = new long[1];
        MapSqlParameterSource params = new MapSqlParameterSource("today", Date.valueOf(today));
        String scope = inScope(params);
        jdbc.query("""
                SELECT k.status, count(*) AS n,
                       count(*) FILTER (WHERE k.status = 'SENT' AND k.sign_by IS NOT NULL AND k.sign_by < :today)
                           AS overdue,
                       count(*) FILTER (WHERE k.terms_review = 'PENDING') AS terms_pending"""
                + FROM + " WHERE TRUE" + scope + " GROUP BY k.status", params, rs -> {
                    byStatus.put(SalesContractStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                    overdue[0] += rs.getLong("overdue");
                    pending[0] += rs.getLong("terms_pending");
                });
        return new Summary(byStatus, byStatus.getOrDefault(SalesContractStatus.CUSTOMER_SIGNED, 0L), overdue[0],
                pending[0]);
    }

    /**
     * The caller's data scope over the order, as a condition on {@code o}: see the class comment.
     *
     * @throws ScopeViolationException when no scope is in force, or OWN without a user id, or WAREHOUSE (an order has
     *         no warehouse) - loud, as the scoped repositories are, never every row
     */
    private static String inScope(MapSqlParameterSource params) {
        DataScopeContext.Scope scope = DataScopeContext.current().orElseThrow(() -> new ScopeViolationException(
                "No data scope in force for the contract list: annotate the endpoint with @RequiresPermission"));
        return switch (scope.effective()) {
            case ALL -> "";
            case OWN -> {
                if (scope.user().userId() == null) {
                    throw new ScopeViolationException("OWN scope requires a user id, and the current principal has "
                            + "none");
                }
                params.addValue("scopeOwner", scope.user().userId());
                yield " AND o.customer_id = :scopeOwner";
            }
            // As DataScopeSpecifications: team membership is not known yet, so TEAM sees nothing rather than all.
            case TEAM -> " AND FALSE";
            case WAREHOUSE -> throw new ScopeViolationException("An order has no warehouse: WAREHOUSE scope cannot be "
                    + "applied to the contract list");
        };
    }

    private static String where(Query query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("\n WHERE TRUE");
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            where.append(" AND k.status IN (:statuses)");
            params.addValue("statuses", query.statuses().stream().map(Enum::name).toList());
        }
        if (query.salesOwnerId() != null) {
            where.append(" AND k.sales_owner_id = :owner");
            params.addValue("owner", query.salesOwnerId());
        }
        if (query.signByBefore() != null) {
            where.append(" AND k.status = 'SENT' AND k.sign_by < :signByBefore");
            params.addValue("signByBefore", Date.valueOf(query.signByBefore()));
        }
        if (query.termsReview() != null) {
            where.append(" AND k.terms_review = :termsReview");
            params.addValue("termsReview", query.termsReview().name());
        }
        if (query.search() != null && !query.search().isBlank()) {
            where.append(" AND (lower(k.contract_number) LIKE :q ESCAPE '\\' OR lower(o.order_number) LIKE :q ESCAPE '\\'"
                    + " OR lower(k.buyer_name) LIKE :q ESCAPE '\\')");
            params.addValue("q", "%" + escapeLike(query.search().strip().toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        return where.toString();
    }

    /** A user's text as a LIKE operand: the wildcards are text. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
