package com.stockflow.fulfillment.internal.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Pick task numbers {@code PK-yyyyMMdd-nnnn} from {@code platform.document_sequence}, as {@link LoadNumbers} does for
 * loads: a number people read off a scanner and say aloud, where the task had only its UUID. Drawn in the transaction
 * that opens the task (its row lock on the day's sequence row is held until commit, as for every document number).
 * {@code tg_pick_task_number} draws from the same sequence for a row inserted without one, so the two never collide.
 */
@Component
public class PickNumbers {

    private final EntityManager entityManager;

    PickNumbers(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public String next(LocalDate day) {
        Number value = (Number) entityManager.createNativeQuery("""
                        INSERT INTO platform.document_sequence (document_type, sequence_date, last_value)
                        VALUES ('PK', :day, 1)
                        ON CONFLICT (document_type, sequence_date)
                        DO UPDATE SET last_value = platform.document_sequence.last_value + 1
                        RETURNING last_value""")
                .setParameter("day", day)
                .getSingleResult();
        return "PK-%s-%04d".formatted(day.format(DateTimeFormatter.BASIC_ISO_DATE), value.longValue());
    }
}
