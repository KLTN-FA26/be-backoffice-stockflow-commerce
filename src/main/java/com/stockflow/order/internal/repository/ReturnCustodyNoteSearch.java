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

    private static final String FROM = """
              FROM ordering.return_request_evidence e
              JOIN ordering.return_request r ON r.id = e.return_request_id
              JOIN ordering.customer_order o ON o.id = r.order_id
              LEFT JOIN ordering.return_custody_outcome c ON c.evidence_id = e.id
              LEFT JOIN ordering.return_request t ON t.id = c.taken_by_return_id
             WHERE e.stage = 'CUSTODY'""";

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
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("""
                SELECT e.id AS evidence_id, r.id AS return_request_id, r.rma_number, r.order_id, r.order_number,
                       r.customer_id, o.warehouse_id, e.custody_sku, e.custody_lpn, e.custody_quantity,
                       e.custody_note, e.uploaded_by, e.uploaded_at, c.choice, c.choice_note, c.taken_by_return_id,
                       t.rma_number AS taken_by_rma_number, c.chosen_by, c.chosen_at, c.ended_at, c.ended_by,
                       c.ended_note
                """ + FROM + where(ended, returnRequestId, warehouses, params)
                + (ended ? " ORDER BY c.ended_at DESC, e.id" : " ORDER BY e.uploaded_at, e.id")
                + " OFFSET :offset LIMIT :limit", params, ReturnCustodyNoteSearch::row);
    }

    /** How many {@link #page} lists in all. */
    public long count(boolean ended, UUID returnRequestId, Collection<UUID> warehouses) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM + where(ended, returnRequestId, warehouses, params),
                params, Long.class);
        return total == null ? 0 : total;
    }

    private static String where(boolean ended, UUID returnRequestId, Collection<UUID> warehouses,
                                MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(ended ? " AND c.ended_at IS NOT NULL" : " AND c.ended_at IS NULL");
        if (returnRequestId != null) {
            where.append(" AND r.id = :request");
            params.addValue("request", returnRequestId);
        }
        if (warehouses != null) {
            if (warehouses.isEmpty()) {
                // A caller limited to warehouses and assigned none sees nothing - never everything.
                where.append(" AND FALSE");
            } else {
                where.append(" AND o.warehouse_id IN (:warehouses)");
                params.addValue("warehouses", List.copyOf(warehouses));
            }
        }
        return where.toString();
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
