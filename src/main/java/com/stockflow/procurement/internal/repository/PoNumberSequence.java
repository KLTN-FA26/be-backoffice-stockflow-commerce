package com.stockflow.procurement.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Hands out the next daily sequence number for a PO number. Same pattern, same reasoning, as
 * {@code order.internal.repository.OrderNumberSequence} — see its javadoc for why an atomic
 * {@code UPDATE ... RETURNING} on a one-row-per-day table beats {@code select max(...) + 1} or a
 * plain Postgres sequence, and why {@code REQUIRES_NEW} is load-bearing (the counter must advance
 * even if the PO that asked for it then fails to save, or two failed attempts could be handed the
 * same number).
 */
@Component
class PoNumberSequence {

    private final EntityManager entityManager;

    PoNumberSequence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextFor(LocalDate date) {
        entityManager.createNativeQuery("""
                        insert into procurement.po_number_sequence (sequence_date, last_value)
                        values (:d, 0)
                        on conflict (sequence_date) do nothing
                        """)
                .setParameter("d", date)
                .executeUpdate();

        Object value = entityManager.createNativeQuery("""
                        update procurement.po_number_sequence
                        set last_value = last_value + 1
                        where sequence_date = :d
                        returning last_value
                        """)
                .setParameter("d", date)
                .getSingleResult();

        return ((Number) value).longValue();
    }
}
