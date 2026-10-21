package com.stockflow.fulfillment.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Load (manifest) numbers {@code LD-yyyyMMdd-nnnn} from {@code platform.document_sequence}, as TO-, GR-, LSX- are;
 * and the lock that makes opening a load idempotent under concurrency.
 */
@Component
public class LoadNumbers {

    private final EntityManager entityManager;

    LoadNumbers(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Serialises the transactions opening a load with this request id, until they end. */
    public void lockRequest(java.util.UUID requestId) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "dispatch-load:" + requestId).getSingleResult();
    }

    public String next(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('LD', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "LD-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }
}
