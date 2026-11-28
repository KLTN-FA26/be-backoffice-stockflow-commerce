package com.stockflow.inventory.internal.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * The inventory documents still open on one carton (the carton investigation, {@code CartonHistoryQueries}): the
 * counts with a line for it that are not finished, and its adjustments waiting for approval. One statement each, on
 * this module's own tables; a carton is on a handful of them at most.
 *
 * <p>"Open" is the count's own: PLANNED or IN_PROGRESS. A submitted count whose variance waits for approval is not
 * listed as a count - its pending {@code COUNT_VARIANCE} adjustment is, which is what still waits on someone.</p>
 */
@Repository
public class CartonDocumentsQuery {

    /** More than a carton is ever on at once; a bound, not a page. */
    static final int AT_MOST = 10;

    private final NamedParameterJdbcTemplate jdbc;

    public CartonDocumentsQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The open counts with a line for the carton, the newest first. */
    public List<Document> openCounts(UUID stockPackageId) {
        return jdbc.query("""
                        SELECT DISTINCT c.id, c.count_number, c.status, c.created_at
                          FROM inventory.cycle_count c
                          JOIN inventory.cycle_count_line l ON l.cycle_count_id = c.id
                         WHERE l.stock_package_id = :carton AND c.status IN ('PLANNED', 'IN_PROGRESS')
                         ORDER BY c.created_at DESC
                         LIMIT :limit""",
                new MapSqlParameterSource("carton", stockPackageId).addValue("limit", AT_MOST),
                (rs, n) -> new Document(rs.getObject("id", UUID.class), rs.getString("count_number"),
                        rs.getString("status")));
    }

    /** The carton's adjustments waiting for approval, asked by hand or a count's variance, the newest first. */
    public List<Document> pendingAdjustments(UUID stockPackageId) {
        return jdbc.query("""
                        SELECT a.id, a.adjustment_number, a.status
                          FROM inventory.stock_adjustment a
                         WHERE a.stock_package_id = :carton AND a.status = 'PENDING_APPROVAL'
                         ORDER BY a.created_at DESC
                         LIMIT :limit""",
                new MapSqlParameterSource("carton", stockPackageId).addValue("limit", AT_MOST),
                (rs, n) -> new Document(rs.getObject("id", UUID.class), rs.getString("adjustment_number"),
                        rs.getString("status")));
    }

    /** An open document: its id, number and status. */
    public record Document(UUID id, String number, String status) {
    }
}
