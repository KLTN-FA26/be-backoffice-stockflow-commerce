package com.stockflow.order.internal.repository;

import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.internal.domain.DeliveryFailureCase;
import com.stockflow.order.internal.domain.DeliveryFailureCases;
import jakarta.persistence.EntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL on {@code ordering.delivery_failure_case}, its cartons and its holds, as
 * {@link DeliveryBatchRecordsAdapter}: a case is a handful of columns that move a few times, and its lines are read
 * from the returned shipment ({@code ordering.order_shipment_line}) rather than copied. Through the
 * {@code EntityManager}, not plain JDBC, so that what the same transaction changed through JPA - the shipment made
 * RETURNED, the fee item just opened - is flushed before these statements and their triggers read it.
 *
 * <p>The table's trigger ({@code ordering.check_delivery_failure_case}) checks every write against the shipment, the
 * receipt and the case's own lifecycle.</p>
 */
@Repository
class DeliveryFailureCasesAdapter implements DeliveryFailureCases {

    private static final String COLUMNS = """
            c.id, c.order_id, c.shipment_id, c.delivery_batch_id, c.receipt_id, c.receipt_number, c.warehouse_id,
            c.failure_reason, c.failure_note, c.opened_at, c.status, c.decided_by, c.decided_at, c.decision_note,
            c.failed_trip_fee, c.fee_installment_id, c.carrier_claim, c.released_at, c.released_by,
            c.redelivery_shipment_id, c.redelivered_at, c.closed_at, c.closed_by, c.version""";

    private final EntityManager entityManager;

    DeliveryFailureCasesAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public DeliveryFailureCase save(DeliveryFailureCase c) {
        boolean exists = !entityManager.createNativeQuery(
                        "SELECT 1 FROM ordering.delivery_failure_case WHERE id = :id")
                .setParameter("id", c.id()).getResultList().isEmpty();
        if (!exists) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.delivery_failure_case (id, order_id, shipment_id, delivery_batch_id,
                                receipt_id, receipt_number, warehouse_id, failure_reason, failure_note, status,
                                opened_at)
                            VALUES (:id, :order, :shipment, CAST(:batch AS UUID), :receipt, :receiptNumber, :warehouse,
                                    :reason, CAST(:note AS VARCHAR), :status, :openedAt)""")
                    .setParameter("id", c.id()).setParameter("order", c.orderId())
                    .setParameter("shipment", c.shipmentId()).setParameter("batch", c.deliveryBatchId())
                    .setParameter("receipt", c.receiptId()).setParameter("receiptNumber", c.receiptNumber())
                    .setParameter("warehouse", c.warehouseId()).setParameter("reason", c.failureReason().name())
                    .setParameter("note", c.failureNote()).setParameter("status", c.status().name())
                    .setParameter("openedAt", Timestamp.from(c.openedAt()))
                    .executeUpdate();
            for (DeliveryFailureCase.Carton carton : c.cartons()) {
                entityManager.createNativeQuery("""
                                INSERT INTO ordering.delivery_failure_case_carton (case_id, lpn, sku, lot_number,
                                                                                   quantity, condition)
                                VALUES (:id, :lpn, :sku, CAST(:lot AS VARCHAR), :quantity, CAST(:condition AS VARCHAR))""")
                        .setParameter("id", c.id()).setParameter("lpn", carton.lpn()).setParameter("sku", carton.sku())
                        .setParameter("lot", carton.lotNumber()).setParameter("quantity", carton.quantity())
                        .setParameter("condition", carton.condition())
                        .executeUpdate();
            }
            return find(c.id()).orElseThrow();
        }
        int updated = entityManager.createNativeQuery("""
                        UPDATE ordering.delivery_failure_case
                           SET status = :status,
                               decided_by = CAST(:decidedBy AS UUID),
                               decided_at = CAST(:decidedAt AS TIMESTAMPTZ),
                               decision_note = CAST(:decisionNote AS VARCHAR),
                               failed_trip_fee = CAST(:fee AS NUMERIC),
                               fee_installment_id = CAST(:feeItem AS UUID),
                               carrier_claim = :carrierClaim,
                               released_at = CAST(:releasedAt AS TIMESTAMPTZ),
                               released_by = CAST(:releasedBy AS UUID),
                               redelivery_shipment_id = CAST(:redelivery AS UUID),
                               redelivered_at = CAST(:redeliveredAt AS TIMESTAMPTZ),
                               closed_at = CAST(:closedAt AS TIMESTAMPTZ),
                               closed_by = CAST(:closedBy AS UUID),
                               version = version + 1
                         WHERE id = :id AND version = :version""")
                .setParameter("status", c.status().name())
                .setParameter("decidedBy", c.decidedBy()).setParameter("decidedAt", timestamp(c.decidedAt()))
                .setParameter("decisionNote", c.decisionNote()).setParameter("fee", c.failedTripFee())
                .setParameter("feeItem", c.feeInstallmentId()).setParameter("carrierClaim", c.carrierClaim())
                .setParameter("releasedAt", timestamp(c.releasedAt())).setParameter("releasedBy", c.releasedBy())
                .setParameter("redelivery", c.redeliveryShipmentId())
                .setParameter("redeliveredAt", timestamp(c.redeliveredAt()))
                .setParameter("closedAt", timestamp(c.closedAt())).setParameter("closedBy", c.closedBy())
                .setParameter("id", c.id()).setParameter("version", c.version())
                .executeUpdate();
        if (updated != 1) {
            // Every writer holds the order's row lock, so this is a stale copy written twice, not a race.
            throw new OptimisticLockingFailureException("Delivery failure case %s changed since it was read"
                    .formatted(c.id()));
        }
        return find(c.id()).orElseThrow();
    }

    @Override
    public Optional<DeliveryFailureCase> find(UUID id) {
        return queryBy("WHERE c.id = :value ORDER BY c.opened_at, c.id", id, 0).stream().findFirst();
    }

    @Override
    public Optional<DeliveryFailureCase> findByShipment(UUID shipmentId) {
        return queryBy("WHERE c.shipment_id = :value ORDER BY c.opened_at, c.id", shipmentId, 0).stream().findFirst();
    }

    @Override
    public List<DeliveryFailureCase> ofOrder(UUID orderId) {
        return queryBy("WHERE c.order_id = :value ORDER BY c.opened_at, c.id", orderId, 0);
    }

    @Override
    public List<DeliveryFailureCase> list(DeliveryFailureCase.Status status, Collection<UUID> warehouses, int limit) {
        if (warehouses != null && warehouses.isEmpty()) {
            return List.of();
        }
        StringBuilder where = new StringBuilder("WHERE TRUE");
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (status != null) {
            where.append(" AND c.status = :status");
            parameters.put("status", status.name());
        }
        if (warehouses != null) {
            where.append(" AND c.warehouse_id IN (:warehouses)");
            parameters.put("warehouses", List.copyOf(warehouses));
        }
        return query(where.append(" ORDER BY c.opened_at, c.id").toString(), parameters, limit);
    }

    @Override
    public List<UUID> ordersWantingGoodsHeld(Collection<String> skus) {
        if (skus != null && skus.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT c.order_id, MIN(c.opened_at) AS first_opened FROM ordering.delivery_failure_case c
                 WHERE (c.status = 'OPEN' OR (c.status = 'REDELIVERY' AND c.released_at IS NULL))""");
        if (skus != null) {
            // The case's cartons name their SKU: the goods it waits for are of these.
            sql.append("""

                    AND EXISTS (SELECT 1 FROM ordering.delivery_failure_case_carton cc
                                 WHERE cc.case_id = c.id AND cc.sku IN (:skus))""");
        }
        sql.append(" GROUP BY c.order_id ORDER BY first_opened, c.order_id");
        var select = entityManager.createNativeQuery(sql.toString());
        if (skus != null) {
            select.setParameter("skus", List.copyOf(skus));
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = select.getResultList();
        return rows.stream().map(r -> (UUID) r[0]).toList();
    }

    @Override
    public List<UUID> ordersReturnedByCancellation(int skip, int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT o.id, MIN(c.closed_at) AS first_closed
                          FROM ordering.customer_order o
                          JOIN ordering.delivery_failure_case c ON c.order_id = o.id AND c.status = 'CANCELLED'
                         WHERE o.status = 'RETURNED'
                           AND NOT EXISTS (SELECT 1 FROM ordering.delivery_failure_case open
                                            WHERE open.order_id = o.id AND open.status IN ('OPEN', 'REDELIVERY'))
                           AND NOT EXISTS (SELECT 1 FROM ordering.order_shipment s
                                            WHERE s.order_id = o.id AND s.status = 'DELIVERED')
                         GROUP BY o.id ORDER BY first_closed, o.id""")
                .setFirstResult(skip).setMaxResults(limit).getResultList();
        return rows.stream().map(r -> (UUID) r[0]).toList();
    }

    @Override
    public boolean hasUndecided(UUID orderId) {
        return !entityManager.createNativeQuery("""
                        SELECT 1 FROM ordering.delivery_failure_case
                         WHERE order_id = :order AND status IN ('OPEN', 'REDELIVERY') LIMIT 1""")
                .setParameter("order", orderId).getResultList().isEmpty();
    }

    @Override
    public List<Hold> holdsOf(UUID caseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT reservation_id, case_id, order_line_id FROM ordering.delivery_failure_case_hold
                         WHERE case_id = :id ORDER BY created_at, reservation_id""")
                .setParameter("id", caseId).getResultList();
        return rows.stream().map(r -> new Hold((UUID) r[0], (UUID) r[1], (UUID) r[2])).toList();
    }

    @Override
    public void recordHolds(UUID caseId, UUID orderLineId, Collection<UUID> reservationIds) {
        for (UUID reservationId : reservationIds) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.delivery_failure_case_hold (reservation_id, case_id, order_line_id)
                            VALUES (:reservation, :id, :line)
                            ON CONFLICT (reservation_id) DO NOTHING""")
                    .setParameter("reservation", reservationId).setParameter("id", caseId)
                    .setParameter("line", orderLineId).executeUpdate();
        }
    }

    @Override
    public void carry(UUID replaced, Collection<UUID> replacements) {
        for (UUID replacement : replacements) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.delivery_failure_case_hold (reservation_id, case_id, order_line_id)
                            SELECT :replacement, h.case_id, h.order_line_id
                              FROM ordering.delivery_failure_case_hold h WHERE h.reservation_id = :replaced
                            ON CONFLICT (reservation_id) DO NOTHING""")
                    .setParameter("replacement", replacement).setParameter("replaced", replaced).executeUpdate();
        }
    }

    /** The cases {@code where} keeps, each with its shipment's lines and its cartons: three statements, not 2n + 1. */
    private List<DeliveryFailureCase> queryBy(String where, Object value, int limit) {
        return query(where, value == null ? Map.of() : Map.of("value", value), limit);
    }

    private List<DeliveryFailureCase> query(String where, Map<String, Object> parameters, int limit) {
        var select = entityManager.createNativeQuery("SELECT " + COLUMNS + " FROM ordering.delivery_failure_case c "
                + where);
        parameters.forEach(select::setParameter);
        if (limit > 0) {
            select.setMaxResults(limit);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = select.getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, Map<UUID, Integer>> units = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> lines = entityManager.createNativeQuery("""
                        SELECT c.id, l.order_line_id, l.quantity
                          FROM ordering.delivery_failure_case c
                          JOIN ordering.order_shipment_line l ON l.shipment_id = c.shipment_id
                         WHERE c.id IN (:ids)""")
                .setParameter("ids", ids).getResultList();
        for (Object[] line : lines) {
            units.computeIfAbsent((UUID) line[0], k -> new LinkedHashMap<>())
                    .merge((UUID) line[1], ((Number) line[2]).intValue(), Integer::sum);
        }
        Map<UUID, List<DeliveryFailureCase.Carton>> cartons = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object[]> cartonRows = entityManager.createNativeQuery("""
                        SELECT case_id, lpn, sku, lot_number, quantity, condition
                          FROM ordering.delivery_failure_case_carton WHERE case_id IN (:ids) ORDER BY case_id, lpn""")
                .setParameter("ids", ids).getResultList();
        for (Object[] carton : cartonRows) {
            cartons.computeIfAbsent((UUID) carton[0], k -> new ArrayList<>()).add(new DeliveryFailureCase.Carton(
                    (String) carton[1], (String) carton[2], (String) carton[3], ((Number) carton[4]).intValue(),
                    (String) carton[5]));
        }
        return rows.stream().map(r -> new DeliveryFailureCase((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3],
                (UUID) r[4], (String) r[5], (UUID) r[6], DeliveryFailureReason.valueOf((String) r[7]), (String) r[8],
                units.getOrDefault((UUID) r[0], Map.of()), cartons.getOrDefault((UUID) r[0], List.of()),
                instant(r[9]), DeliveryFailureCase.Status.valueOf((String) r[10]), (UUID) r[11], instant(r[12]),
                (String) r[13], (BigDecimal) r[14], (UUID) r[15], Boolean.TRUE.equals(r[16]), instant(r[17]),
                (UUID) r[18], (UUID) r[19], instant(r[20]), instant(r[21]), (UUID) r[22],
                ((Number) r[23]).longValue())).toList();
    }

    private static Timestamp timestamp(Instant at) {
        return at == null ? null : Timestamp.from(at);
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
