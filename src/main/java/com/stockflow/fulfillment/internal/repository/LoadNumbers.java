package com.stockflow.fulfillment.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Load (manifest) numbers {@code LD-yyyyMMdd-nnnn} from {@code platform.document_sequence}, as GR- are. Loads are
 * born from load plans, whose own request lock ({@code LoadPlanNumbers.lockRequest}) makes them idempotent.
 */
@Component
public class LoadNumbers {

    private final EntityManager entityManager;

    LoadNumbers(EntityManager entityManager) {
        this.entityManager = entityManager;
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
