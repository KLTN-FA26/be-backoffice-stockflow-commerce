package com.stockflow.fulfillment.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Load plan numbers {@code LP-yyyyMMdd-nnnn} from {@code platform.document_sequence}, as {@link LoadNumbers} does for
 * loads; and the lock that makes creating a plan idempotent under concurrency.
 */
@Component
public class LoadPlanNumbers {

    private final EntityManager entityManager;

    LoadPlanNumbers(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Serialises the transactions creating a plan with this request id, until they end. */
    public void lockRequest(UUID requestId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "load-plan:" + requestId).getSingleResult();
    }

    public String next(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('LP', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "LP-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }
}
