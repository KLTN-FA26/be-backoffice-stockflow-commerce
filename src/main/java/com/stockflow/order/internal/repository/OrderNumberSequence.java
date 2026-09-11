package com.stockflow.order.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Hands out the next daily sequence number for {@code OrderNumber}.
 *
 * <p><b>Why a table and an UPDATE rather than counting rows.</b> {@code select max(...) + 1} loses
 * a race the moment two checkouts overlap, and a Postgres sequence cannot reset per day. An
 * atomic {@code UPDATE ... RETURNING} on a one-row-per-day table is safe under any concurrency
 * because the row lock is held for the duration of the statement.</p>
 *
 * <p><b>{@code REQUIRES_NEW} is load-bearing.</b> The counter must advance even if the order that
 * asked for it then fails — otherwise the failed attempt's number would be handed out again and
 * two different orders could end up sharing one. Gaps in the sequence are acceptable; duplicates
 * are not. A side effect worth having: the number is committed before the caller's transaction
 * ends, so it never holds the counter row's lock while doing anything else.</p>
 */
@Component
class OrderNumberSequence {

    private final EntityManager entityManager;

    OrderNumberSequence(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * <b>Public, not package-private, and that is not a style choice.</b> Spring's proxy-based
     * transaction advice is applied to public methods only — {@code AnnotationTransactionAttribute​Source}
     * returns no attribute for anything else — so a package-private {@code @Transactional} method
     * silently runs with whatever transaction the caller already had. Here that would mean the
     * counter joining the checkout transaction, so a failed checkout would roll the counter back
     * and the next order would be handed a number that is already taken.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextFor(LocalDate date) {
        entityManager.createNativeQuery("""
                        insert into ordering.order_number_sequence (sequence_date, last_value)
                        values (:d, 0)
                        on conflict (sequence_date) do nothing
                        """)
                .setParameter("d", date)
                .executeUpdate();

        Object value = entityManager.createNativeQuery("""
                        update ordering.order_number_sequence
                        set last_value = last_value + 1
                        where sequence_date = :d
                        returning last_value
                        """)
                .setParameter("d", date)
                .getSingleResult();

        return ((Number) value).longValue();
    }
}
