package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entries of an order's history that move no status (WF-05 BE-52): a refund record voided while a further claim of the
 * order was open, or its goods back - the claim decides where the order goes, so no status row says the void happened.
 * {@code ordering.order_refund_void}, append-only; listed for staff in {@code GET /orders/{id}/history} as
 * {@code REFUND_VOIDED}, as the closures of the rest are ({@code order_remainder_closure}). Cartons removed at the
 * check station ({@code order_carton_removal}, WF4-F2C) are listed as {@code CARTON_REMOVAL}.
 */
@org.springframework.stereotype.Repository
public class OrderHistoryNotes {

    /** A refund record voided while the order stayed where it was. */
    public record RefundVoid(UUID refundRecordId, String reason, UUID voidedBy, Instant voidedAt) {
    }

    /** A carton the check station removed from the order, with what became of the order (WF4-F2C). */
    public record CartonRemoval(UUID packageId, String lpn, String reason, String note, boolean continuesPartial,
                                UUID removedBy, Instant removedAt) {
    }

    private final EntityManager entityManager;

    public OrderHistoryNotes(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Records the void once per refund record: an event handled twice writes one row. In the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundVoided(UUID orderId, UUID refundRecordId, String reason, UUID by, Instant at) {
        String note = reason == null || reason.isBlank() ? null : reason.strip();
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_refund_void (id, order_id, refund_record_id, reason, voided_by,
                                                                voided_at)
                        VALUES (:id, :order, :record, CAST(:reason AS VARCHAR), CAST(:by AS UUID), :at)
                        ON CONFLICT (refund_record_id) DO NOTHING""")
                .setParameter("id", Identifiers.newId()).setParameter("order", orderId)
                .setParameter("record", refundRecordId)
                .setParameter("reason", note == null || note.length() <= 500 ? note : note.substring(0, 500))
                .setParameter("by", by).setParameter("at", Timestamp.from(at))
                .executeUpdate();
    }

    /**
     * WF4-F2C (08 §4.2 step 4): a carton removed at the check station, once per package - a request handled twice
     * writes one row. In the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cartonRemoved(UUID orderId, UUID packageId, String lpn, String reason, String note,
                              boolean continuesPartial, UUID by, Instant at) {
        String text = note == null || note.isBlank() ? null : note.strip();
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_carton_removal (id, order_id, package_id, lpn, reason, note,
                                                                   continues_partial, removed_by, removed_at)
                        VALUES (:id, :order, :package, :lpn, :reason, CAST(:note AS VARCHAR), :partial,
                                CAST(:by AS UUID), :at)
                        ON CONFLICT (package_id) DO NOTHING""")
                .setParameter("id", Identifiers.newId()).setParameter("order", orderId)
                .setParameter("package", packageId).setParameter("lpn", lpn).setParameter("reason", reason)
                .setParameter("note", text == null || text.length() <= 500 ? text : text.substring(0, 500))
                .setParameter("partial", continuesPartial).setParameter("by", by)
                .setParameter("at", Timestamp.from(at))
                .executeUpdate();
    }

    /** The cartons the station removed from the order, oldest first. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<CartonRemoval> cartonRemovals(UUID orderId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT package_id, lpn, reason, note, continues_partial, removed_by, removed_at
                          FROM ordering.order_carton_removal WHERE order_id = :order ORDER BY removed_at, id""")
                .setParameter("order", orderId).getResultList();
        List<CartonRemoval> removals = new ArrayList<>();
        for (Object[] row : rows) {
            removals.add(new CartonRemoval((UUID) row[0], (String) row[1], (String) row[2], (String) row[3],
                    (Boolean) row[4], (UUID) row[5], instant(row[6])));
        }
        return removals;
    }

    /**
     * WF-05 Q-12: Sales' closure of a lost shipment - why, the carrier's confirmation file, who and when, and the key
     * payment settled it under ({@code ordering.order_shipment_loss_closure}, one per shipment). In the caller's
     * transaction, with the closure.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lossClosed(UUID orderId, UUID shipmentId, UUID closureId, String note, String evidenceKey,
                           String evidenceName, UUID by, Instant at) {
        entityManager.createNativeQuery("""
                        INSERT INTO ordering.order_shipment_loss_closure (shipment_id, order_id, closure_id, note,
                                                                          evidence_key, evidence_name, closed_by,
                                                                          closed_at)
                        VALUES (:shipment, :order, :closure, :note, :key, CAST(:name AS VARCHAR), :by, :at)""")
                .setParameter("shipment", shipmentId).setParameter("order", orderId)
                .setParameter("closure", closureId).setParameter("note", note).setParameter("key", evidenceKey)
                .setParameter("name", evidenceName == null || evidenceName.length() <= 255 ? evidenceName
                        : evidenceName.substring(0, 255))
                .setParameter("by", by).setParameter("at", Timestamp.from(at))
                .executeUpdate();
    }

    /**
     * Those of {@code shipmentIds} Sales closed as lost (WF-05 Q-12): a row of {@code order_shipment_loss_closure}, kept
     * for good. Not the shipment's status: a lost shipment the carrier found later may be recorded RETURNED, and a
     * shipment reported back whose goods never reached the warehouse is closed as lost while RETURNED (L-I6).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public java.util.Set<UUID> lostShipments(java.util.Collection<UUID> shipmentIds) {
        if (shipmentIds == null || shipmentIds.isEmpty()) {
            return java.util.Set.of();
        }
        List<UUID> rows = entityManager.createNativeQuery("""
                        SELECT shipment_id FROM ordering.order_shipment_loss_closure
                         WHERE shipment_id IN (:shipments)""")
                .setParameter("shipments", List.copyOf(shipmentIds)).getResultList();
        return java.util.Set.copyOf(rows);
    }

    /** The order's refund voids that moved no status, oldest first. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<RefundVoid> refundVoids(UUID orderId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT refund_record_id, reason, voided_by, voided_at FROM ordering.order_refund_void
                         WHERE order_id = :order ORDER BY voided_at, id""")
                .setParameter("order", orderId).getResultList();
        List<RefundVoid> voids = new ArrayList<>();
        for (Object[] row : rows) {
            voids.add(new RefundVoid((UUID) row[0], (String) row[1], (UUID) row[2], instant(row[3])));
        }
        return voids;
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant at) {
            return at;
        }
        if (value instanceof java.time.OffsetDateTime at) {
            return at.toInstant();
        }
        return ((Timestamp) value).toInstant();
    }
}
