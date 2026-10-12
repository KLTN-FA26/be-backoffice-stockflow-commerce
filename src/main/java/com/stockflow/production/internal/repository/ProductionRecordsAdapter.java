package com.stockflow.production.internal.repository;

import com.stockflow.production.internal.domain.ProductionRecords;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL for three append-only tables: rows are inserted once and read back as records, never updated,
 * so an entity with dirty checking and a version would buy nothing. The persistence context is flushed
 * before each insert, so the rows it references are in the database, and a broken constraint fails the
 * step that broke it.
 */
@Repository("productionWorkshopRecords")   // order has a ProductionRecordsAdapter of its own
class ProductionRecordsAdapter implements ProductionRecords {

    private static final String ISSUE_COLUMNS = "id, production_order_id, request_id, reservation_id, sku, lot_number, "
            + "from_location, to_location, quantity, stock_movement_id, issued_at, issued_by";

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    ProductionRecordsAdapter(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<MaterialIssue> findIssue(UUID requestId) {
        return jdbc.query("SELECT " + ISSUE_COLUMNS + " FROM production.production_material_issue WHERE request_id = ?",
                ProductionRecordsAdapter::issue, requestId).stream().findFirst();
    }

    @Override
    public List<MaterialIssue> issuesOf(UUID productionOrderId) {
        return jdbc.query("SELECT " + ISSUE_COLUMNS + " FROM production.production_material_issue "
                + "WHERE production_order_id = ? ORDER BY issued_at, id", ProductionRecordsAdapter::issue, productionOrderId);
    }

    @Override
    public void recordIssue(MaterialIssue issue) {
        entityManager.flush();
        jdbc.update("INSERT INTO production.production_material_issue (" + ISSUE_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                issue.id(), issue.productionOrderId(), issue.requestId(), issue.reservationId(), issue.sku(),
                issue.lotNumber(), issue.fromLocation(), issue.toLocation(), issue.quantity(), issue.stockMovementId(),
                Timestamp.from(issue.issuedAt()), issue.issuedBy());
    }

    @Override
    public List<QcRound> qcRoundsOf(UUID productionOrderId) {
        return jdbc.query("""
                        SELECT id, production_order_id, round_no, passed_quantity, failed_quantity, note, inspected_at,
                               inspected_by
                        FROM production.production_qc_result WHERE production_order_id = ? ORDER BY round_no""",
                (rs, n) -> new QcRound(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        rs.getObject(8, UUID.class)),
                productionOrderId);
    }

    @Override
    public void recordQc(QcRound round) {
        entityManager.flush();
        jdbc.update("""
                        INSERT INTO production.production_qc_result (id, production_order_id, round_no, passed_quantity,
                            failed_quantity, note, inspected_at, inspected_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                round.id(), round.productionOrderId(), round.round(), round.passed(), round.failed(), round.note(),
                Timestamp.from(round.inspectedAt()), round.inspectedBy());
    }

    @Override
    public List<Scrap> scrapOf(UUID productionOrderId) {
        return jdbc.query("""
                        SELECT id, production_order_id, quantity, reason, note, stock_adjustment_id, recorded_at,
                               recorded_by
                        FROM production.production_scrap WHERE production_order_id = ? ORDER BY recorded_at, id""",
                (rs, n) -> new Scrap(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        ScrapReason.valueOf(rs.getString(4)), rs.getString(5), rs.getObject(6, UUID.class),
                        rs.getTimestamp(7).toInstant(), rs.getObject(8, UUID.class)),
                productionOrderId);
    }

    @Override
    public void recordScrap(Scrap scrap) {
        entityManager.flush();
        jdbc.update("""
                        INSERT INTO production.production_scrap (id, production_order_id, quantity, reason, note,
                            stock_adjustment_id, recorded_at, recorded_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                scrap.id(), scrap.productionOrderId(), scrap.quantity(), scrap.reason().name(), scrap.note(),
                scrap.stockAdjustmentId(), Timestamp.from(scrap.recordedAt()), scrap.recordedBy());
    }

    @Override
    public boolean recordSubcontractReceipt(UUID receiptId, UUID productionOrderId, int quantity, java.time.Instant at) {
        entityManager.flush();
        return jdbc.update("""
                        INSERT INTO production.production_subcontract_receipt (receipt_id, production_order_id, quantity,
                            received_at) VALUES (?, ?, ?, ?) ON CONFLICT (receipt_id) DO NOTHING""",
                receiptId, productionOrderId, quantity, Timestamp.from(at)) == 1;
    }

    private static MaterialIssue issue(ResultSet rs, int row) throws SQLException {
        return new MaterialIssue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), rs.getObject(10, UUID.class), rs.getTimestamp(11).toInstant(), rs.getObject(12, UUID.class));
    }
}
