package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.CustodyNoteRow;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The goods kept on custody notes, every return's, for the list the warehouse works from (WF-05 Q-11, TH-6; audit round
 * 3, RAD-3): each note with its return and order, and how it ended so far ({@code ordering.return_custody_outcome}).
 * Plain SQL over the order module's own tables, one statement a page: the warehouse's list is a projection, not a
 * return's aggregate.
 */
@Repository
public class ReturnCustodyNoteSearch {

    /**
     * One constant statement for every filter combination, each filter switched by a parameter, so nothing but bound
     * values ever reaches the SQL text. {@code :scoped} false lists every warehouse; true lists only
     * {@code :warehouses}, which is never empty - a caller limited to warehouses and assigned none gets {@link #NONE},
     * a UUID no row carries, so it sees nothing rather than everything.
     */
    private static final String FROM = """
              FROM ordering.return_request_evidence e
              JOIN ordering.return_request r ON r.id = e.return_request_id
              JOIN ordering.customer_order o ON o.id = r.order_id
              LEFT JOIN ordering.return_custody_outcome c ON c.evidence_id = e.id
              LEFT JOIN ordering.return_request t ON t.id = c.taken_by_return_id
             WHERE e.stage = 'CUSTODY'
               AND (c.ended_at IS NOT NULL) = :ended
               AND (NOT :byRequest OR r.id = :request)
               AND (NOT :scoped OR o.warehouse_id IN (:warehouses))
            """;

    private static final String PAGE = """
            SELECT e.id AS evidence_id, r.id AS return_request_id, r.rma_number, r.order_id, r.order_number,
                   r.customer_id, o.warehouse_id, e.custody_sku, e.custody_lpn, e.custody_quantity,
                   e.custody_note, e.uploaded_by, e.uploaded_at, c.choice, c.choice_note, c.taken_by_return_id,
                   t.rma_number AS taken_by_rma_number, c.chosen_by, c.chosen_at, c.ended_at, c.ended_by,
                   c.ended_note
            """ + FROM + """
             ORDER BY CASE WHEN :ended THEN c.ended_at END DESC,
                      CASE WHEN :ended THEN NULL ELSE e.uploaded_at END,
                      e.id
            OFFSET :offset LIMIT :limit
            """;

    private static final String COUNT = "SELECT count(*) " + FROM;

    /** Stands in for an absent filter value; no return or order carries it. */
    private static final UUID NONE = new UUID(0L, 0L);

    private final NamedParameterJdbcTemplate jdbc;

    ReturnCustodyNoteSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The custody notes whose goods are still kept ({@code ended} false: no outcome yet, or one chosen and not carried
     * out), oldest first - or those that left ({@code ended} true), latest first; of one return when
     * {@code returnRequestId} is named; of the orders of {@code warehouses} only, when it is not null.
     */
    public List<CustodyNoteRow> page(boolean ended, UUID returnRequestId, Collection<UUID> warehouses, int offset, int limit) {
        MapSqlParameterSource params = filters(ended, returnRequestId, warehouses)
                .addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(PAGE, params, ReturnCustodyNoteSearch::row);
    }

    /** How many {@link #page} lists in all. */
    public long count(boolean ended, UUID returnRequestId, Collection<UUID> warehouses) {
        Long total = jdbc.queryForObject(COUNT, filters(ended, returnRequestId, warehouses), Long.class);
        return total == null ? 0 : total;
    }

    private static MapSqlParameterSource filters(boolean ended, UUID returnRequestId, Collection<UUID> warehouses) {
        return new MapSqlParameterSource()
                .addValue("ended", ended)
                .addValue("byRequest", returnRequestId != null)
                .addValue("request", returnRequestId != null ? returnRequestId : NONE)
                .addValue("scoped", warehouses != null)
                .addValue("warehouses", warehouses == null || warehouses.isEmpty() ? List.of(NONE)
                        : List.copyOf(warehouses));
    }

    private static CustodyNoteRow row(ResultSet rs, int n) throws SQLException {
        return new CustodyNoteRow(rs.getObject("evidence_id", UUID.class), rs.getObject("return_request_id", UUID.class),
                rs.getString("rma_number"), rs.getObject("order_id", UUID.class), rs.getString("order_number"),
                rs.getObject("customer_id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getString("custody_sku"), rs.getString("custody_lpn"), rs.getInt("custody_quantity"),
                rs.getString("custody_note"), rs.getObject("uploaded_by", UUID.class),
                instant(rs.getTimestamp("uploaded_at")), rs.getString("choice"), rs.getString("choice_note"),
                rs.getObject("taken_by_return_id", UUID.class), rs.getString("taken_by_rma_number"),
                rs.getObject("chosen_by", UUID.class), instant(rs.getTimestamp("chosen_at")),
                instant(rs.getTimestamp("ended_at")), rs.getObject("ended_by", UUID.class),
                rs.getString("ended_note"));
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }
}
