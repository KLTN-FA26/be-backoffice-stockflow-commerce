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
    public boolean recordSubcontractReceipt(UUID receiptId, UUID productionOrderId, int quantity, Integer usedBlanks,
                                            java.time.Instant at) {
        entityManager.flush();
        return jdbc.update("""
                        INSERT INTO production.production_subcontract_receipt (receipt_id, production_order_id, quantity,
                            used_blanks, received_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (receipt_id) DO NOTHING""",
                receiptId, productionOrderId, quantity, usedBlanks, Timestamp.from(at)) == 1;
    }

    private static final String RECEIPT_COLUMNS = "receipt_id, production_order_id, quantity, received_at, used_blanks, "
            + "spoilt_blanks, returned_blanks, reconciled_at, reconciled_by";

    @Override
    public List<SubcontractReceipt> subcontractReceiptsOf(UUID productionOrderId) {
        return jdbc.query("SELECT " + RECEIPT_COLUMNS + " FROM production.production_subcontract_receipt "
                        + "WHERE production_order_id = ? ORDER BY received_at, receipt_id",
                ProductionRecordsAdapter::receipt, productionOrderId);
    }

    @Override
    public Optional<SubcontractReceipt> findSubcontractReceiptForUpdate(UUID receiptId) {
        return jdbc.query("SELECT " + RECEIPT_COLUMNS + " FROM production.production_subcontract_receipt "
                + "WHERE receipt_id = ? FOR UPDATE", ProductionRecordsAdapter::receipt, receiptId).stream().findFirst();
    }

    @Override
    public boolean reconcileSubcontractReceipt(UUID receiptId, int spoilt, int returned, java.time.Instant at, UUID by) {
        entityManager.flush();
        return jdbc.update("""
                        UPDATE production.production_subcontract_receipt
                           SET spoilt_blanks = ?, returned_blanks = ?, reconciled_at = ?, reconciled_by = ?
                         WHERE receipt_id = ? AND reconciled_at IS NULL""",
                spoilt, returned, Timestamp.from(at), by, receiptId) == 1;
    }

    @Override
    public void recordSubcontractHold(UUID reservationId, UUID receiptId, UUID receiptLineId, UUID productionOrderId) {
        entityManager.flush();
        jdbc.update("""
                        INSERT INTO production.production_subcontract_hold (reservation_id, receipt_id, receipt_line_id,
                            production_order_id) VALUES (?, ?, ?, ?) ON CONFLICT (reservation_id) DO NOTHING""",
                reservationId, receiptId, receiptLineId, productionOrderId);
    }

    @Override
    public List<UUID> subcontractHoldsOf(UUID productionOrderId) {
        return jdbc.query("""
                        SELECT h.reservation_id FROM production.production_subcontract_hold h
                          JOIN production.production_subcontract_receipt r ON r.receipt_id = h.receipt_id
                         WHERE h.production_order_id = ? ORDER BY r.received_at, h.receipt_line_id""",
                (rs, n) -> rs.getObject(1, UUID.class), productionOrderId);
    }

    private static SubcontractReceipt receipt(ResultSet rs, int row) throws SQLException {
        Timestamp reconciledAt = rs.getTimestamp(8);
        return new SubcontractReceipt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                rs.getTimestamp(4).toInstant(), rs.getObject(5, Integer.class), rs.getObject(6, Integer.class),
                rs.getObject(7, Integer.class), reconciledAt == null ? null : reconciledAt.toInstant(),
                rs.getObject(9, UUID.class));
    }

    private static MaterialIssue issue(ResultSet rs, int row) throws SQLException {
        return new MaterialIssue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), rs.getObject(10, UUID.class), rs.getTimestamp(11).toInstant(), rs.getObject(12, UUID.class));
    }
}
