package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.domain.WindowChangeReason;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Native SQL on {@code ordering.delivery_batch_hold}, {@code delivery_batch_release} and
 * {@code delivery_batch_window_change}, as {@code SettlementCandidatesAdapter}: plain rows written once, no entity
 * lifecycle to gain from JPA. The tables' triggers check every row against the order's signed contract. A hold's row is
 * deleted only when its carton is removed at the check station ({@link #forget}): the batch lacks it again.
 */
@Repository
class DeliveryBatchRecordsAdapter implements DeliveryBatchRecords {

    private final EntityManager entityManager;

    DeliveryBatchRecordsAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<Hold> holdsOf(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT reservation_id, delivery_batch_id, order_line_id FROM ordering.delivery_batch_hold
                         WHERE order_id = :order""")
                .setParameter("order", orderId).getResultList();
        return rows.stream().map(r -> new Hold((UUID) r[0], (UUID) r[1], (UUID) r[2])).toList();
    }

    @Override
    public void recordHolds(UUID orderId, UUID batchId, UUID orderLineId, Collection<UUID> reservationIds) {
        for (UUID reservationId : reservationIds) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id,
                                                                      order_line_id)
                            VALUES (:reservation, :batch, :order, :line)
                            ON CONFLICT (reservation_id) DO NOTHING""")
                    .setParameter("reservation", reservationId).setParameter("batch", batchId)
                    .setParameter("order", orderId).setParameter("line", orderLineId).executeUpdate();
        }
    }

    @Override
    public void carry(UUID replaced, Collection<UUID> replacements) {
        for (UUID replacement : replacements) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.delivery_batch_hold (reservation_id, delivery_batch_id, order_id,
                                                                      order_line_id)
                            SELECT :replacement, h.delivery_batch_id, h.order_id, h.order_line_id
                              FROM ordering.delivery_batch_hold h WHERE h.reservation_id = :replaced
                            ON CONFLICT (reservation_id) DO NOTHING""")
                    .setParameter("replacement", replacement).setParameter("replaced", replaced).executeUpdate();
        }
    }

    @Override
    public boolean forget(UUID orderId, UUID reservationId) {
        return entityManager.createNativeQuery("""
                        DELETE FROM ordering.delivery_batch_hold WHERE reservation_id = :reservation AND order_id = :order""")
                .setParameter("reservation", reservationId).setParameter("order", orderId).executeUpdate() > 0;
    }

    @Override
    public List<Release> releasesOf(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT delivery_batch_id, seq, warehouse_id, released_at, released_by
                          FROM ordering.delivery_batch_release WHERE order_id = :order ORDER BY seq""")
                .setParameter("order", orderId).getResultList();
        return rows.stream().map(r -> new Release((UUID) r[0], ((Number) r[1]).intValue(), (UUID) r[2],
                instant(r[3]), (UUID) r[4])).toList();
    }

    @Override
    public void recordRelease(UUID orderId, Release release) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.delivery_batch_release (delivery_batch_id, order_id, seq, warehouse_id,
                                                                     released_at, released_by)
                        VALUES (:batch, :order, :seq, :warehouse, :at, :by)""")
                .setParameter("batch", release.batchId()).setParameter("order", orderId)
                .setParameter("seq", release.seq()).setParameter("warehouse", release.warehouseId())
                .setParameter("at", Timestamp.from(release.releasedAt())).setParameter("by", release.releasedBy())
                .executeUpdate();
    }

    @Override
    public List<WindowChange> windowChangesOf(UUID orderId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, delivery_batch_id, old_window_start, old_window_end, new_window_start, new_window_end,
                               reason_code, note, evidence_file_key, evidence_file_name, evidence_message_id,
                               changed_by, changed_at
                          FROM ordering.delivery_batch_window_change WHERE order_id = :order
                         ORDER BY changed_at, id""")
                .setParameter("order", orderId).getResultList();
        return rows.stream().map(r -> new WindowChange((UUID) r[0], (UUID) r[1], date(r[2]), date(r[3]), date(r[4]),
                date(r[5]), WindowChangeReason.valueOf((String) r[6]), (String) r[7], (String) r[8], (String) r[9],
                (UUID) r[10], (UUID) r[11], instant(r[12]))).toList();
    }

    @Override
    public void recordWindowChange(UUID orderId, WindowChange change) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id,
                            old_window_start, old_window_end, new_window_start, new_window_end, reason_code, note,
                            evidence_file_key, evidence_file_name, evidence_message_id, changed_by, changed_at)
                        VALUES (:id, :batch, :order, :oldStart, :oldEnd, :newStart, :newEnd, :reason, :note,
                                CAST(:fileKey AS VARCHAR), CAST(:fileName AS VARCHAR), CAST(:message AS UUID), :by,
                                :at)""")
                .setParameter("id", change.id()).setParameter("batch", change.batchId())
                .setParameter("order", orderId)
                .setParameter("oldStart", Date.valueOf(change.oldStart()))
                .setParameter("oldEnd", Date.valueOf(change.oldEnd()))
                .setParameter("newStart", Date.valueOf(change.newStart()))
                .setParameter("newEnd", Date.valueOf(change.newEnd()))
                .setParameter("reason", change.reason().name()).setParameter("note", change.note())
                .setParameter("fileKey", change.evidenceFileKey()).setParameter("fileName", change.evidenceFileName())
                .setParameter("message", change.evidenceMessageId()).setParameter("by", change.changedBy())
                .setParameter("at", Timestamp.from(change.changedAt()))
                .executeUpdate();
    }

    private static LocalDate date(Object value) {
        return value instanceof Date d ? d.toLocalDate() : value instanceof LocalDate l ? l : null;
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp t) {
            return t.toInstant();
        }
        if (value instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return value instanceof Instant i ? i : null;
    }
}
