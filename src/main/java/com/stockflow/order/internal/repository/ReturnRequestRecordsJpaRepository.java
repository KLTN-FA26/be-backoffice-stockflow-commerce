package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.entity.ReturnRequestDiscrepancyJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestEvidenceJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestStatusHistoryJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The append-only rows of a return - photos, discrepancies, status history - written with {@code persist} (they are
 * only ever inserted; a Spring Data {@code save} of an entity with an assigned id would merge, reading first) and read
 * per return with one query each.
 */
@Component
class ReturnRequestRecordsJpaRepository {

    private final EntityManager entityManager;

    ReturnRequestRecordsJpaRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    void insert(Object row) {
        entityManager.persist(row);
    }

    List<ReturnRequestEvidenceJpaEntity> evidenceOf(UUID returnRequestId) {
        return entityManager.createQuery("""
                        select e from ReturnRequestEvidenceJpaEntity e
                        where e.returnRequestId = :id order by e.uploadedAt, e.id""",
                        ReturnRequestEvidenceJpaEntity.class)
                .setParameter("id", returnRequestId)
                .getResultList();
    }

    List<ReturnRequestDiscrepancyJpaEntity> discrepanciesOf(UUID returnRequestId) {
        return entityManager.createQuery("""
                        select d from ReturnRequestDiscrepancyJpaEntity d
                        where d.returnRequestId = :id order by d.recordedAt, d.id""",
                        ReturnRequestDiscrepancyJpaEntity.class)
                .setParameter("id", returnRequestId)
                .getResultList();
    }

    List<ReturnRequestStatusHistoryJpaEntity> historyOf(UUID returnRequestId) {
        return entityManager.createQuery("""
                        select h from ReturnRequestStatusHistoryJpaEntity h
                        where h.returnRequestId = :id order by h.occurredAt, h.id""",
                        ReturnRequestStatusHistoryJpaEntity.class)
                .setParameter("id", returnRequestId)
                .getResultList();
    }
}
