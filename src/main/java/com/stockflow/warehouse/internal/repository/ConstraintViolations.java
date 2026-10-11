package com.stockflow.warehouse.internal.repository;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells which constraint a {@link DataIntegrityViolationException} came from, so an adapter can turn
 * the one it expects into a precise error code instead of the generic {@code 409 DUPLICATE_KEY}
 * (logged at ERROR) that {@code GlobalExceptionHandler} answers for anything unrecognised.
 *
 * <p>The services check uniqueness before writing, which gives the user a good message in the
 * ordinary case; this is the net for two requests racing past that check.</p>
 */
final class ConstraintViolations {

    private ConstraintViolations() {
    }

    static boolean isViolationOf(DataIntegrityViolationException ex, String constraintName) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        // Hibernate extracts the name for the PostgreSQL error codes it knows; the message is the
        // fallback, and Postgres always quotes the constraint name in it.
        String message = ex.getMostSpecificCause().getMessage();
        return message != null && message.contains("\"" + constraintName + "\"");
    }
}
