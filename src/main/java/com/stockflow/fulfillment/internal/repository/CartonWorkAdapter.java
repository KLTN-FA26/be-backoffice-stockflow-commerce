package com.stockflow.fulfillment.internal.repository;

import com.stockflow.inventory.spi.CartonWork;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory's question of {@code inventory :: spi} for the carton list (#454): which cartons a pick task or a carton
 * check is working on. A pick line still OPEN, or STAGED (the carton waits whole in the staging area, 4C-5), of a pick
 * task not finished; or a carton TO_CHECK / CHECKED in a check session not completed (4C-6). One statement for them all,
 * on fulfillment's own tables; when a carton is in both, the pick is named - it comes first.
 */
@Repository
class CartonWorkAdapter implements CartonWork {

    private final EntityManager entityManager;

    CartonWorkAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Work> openWorkOf(Collection<String> lpns) {
        List<String> wanted = lpns.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT l.lpn, 'PICK' AS kind, p.id AS task_id, 0 AS rank
                          FROM fulfillment.pick_line l
                          JOIN fulfillment.pick p ON p.id = l.pick_id
                         WHERE l.lpn IN (:lpns) AND l.status IN ('OPEN', 'STAGED')
                           AND p.status IN ('PENDING', 'PICKING', 'STAGED')
                        UNION ALL
                        SELECT c.lpn, 'CARTON_CHECK', k.pick_id, 1
                          FROM fulfillment.package c
                          JOIN fulfillment.pack k ON k.id = c.pack_id
                         WHERE c.lpn IN (:lpns) AND c.status IN ('TO_CHECK', 'CHECKED')
                           AND k.status IN ('PENDING', 'IN_PROGRESS')
                         ORDER BY 4, 3""")
                .setParameter("lpns", wanted).getResultList();
        Map<String, Work> work = new HashMap<>();
        for (Object[] row : rows) {
            work.putIfAbsent((String) row[0], new Work(Kind.valueOf((String) row[1]), (UUID) row[2]));
        }
        return work;
    }
}
