package com.stockflow.procurement.internal.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.Timestamp;

/**
 * A {@code TIMESTAMPTZ} column read by a native query arrives as an {@link Instant}, an {@link OffsetDateTime} or a
 * {@link Timestamp} depending on the Hibernate and driver versions; the adapters of this module that read one say so
 * once, here.
 */
final class NativeValues {

    private NativeValues() {
    }

    static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Not a timestamp: " + value.getClass());
        };
    }
}
