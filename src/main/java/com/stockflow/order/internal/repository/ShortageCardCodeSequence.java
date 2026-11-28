package com.stockflow.order.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * The daily counter behind shortage card codes "TT-yyyyMMdd-NNNN" ({@code ordering.shortage_card_code_sequence};
 * UNH-CODE-1128), the way {@code ReturnNumberSequence} counts returns: an atomic {@code UPDATE ... RETURNING} on one
 * row per day, in its own transaction, so a card that fails after taking a code never hands the same code to the next
 * one. Gaps are fine; duplicates are not.
 * Public, because a package-private {@code @Transactional} method is not advised by the proxy.
 */
@Component
class ShortageCardCodeSequence {

    private final EntityManager entityManager;

    ShortageCardCodeSequence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextFor(LocalDate date) {
        entityManager.createNativeQuery("""
                        insert into ordering.shortage_card_code_sequence (sequence_date, last_value)
                        values (:d, 0)
                        on conflict (sequence_date) do nothing""")
                .setParameter("d", date)
                .executeUpdate();
        Object value = entityManager.createNativeQuery("""
                        update ordering.shortage_card_code_sequence
                        set last_value = last_value + 1
                        where sequence_date = :d
                        returning last_value""")
                .setParameter("d", date)
                .getSingleResult();
        return ((Number) value).longValue();
    }
}
