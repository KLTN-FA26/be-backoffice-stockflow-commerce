package com.stockflow.chat.internal.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * On whose behalf messages are read, and so which of them may be read at all. Every read of messages names one: the
 * repository filters on {@link #sees()} in the query itself, which is the one place internal notes are kept from a
 * customer (16 BR-07) - not each controller, not each serializer.
 */
public enum Audience {

    /** The customer: only what was said to them. */
    CUSTOMER(EnumSet.of(Visibility.CUSTOMER)),

    /** The staff: everything, internal notes included. */
    STAFF(EnumSet.of(Visibility.CUSTOMER, Visibility.STAFF));

    private final Set<Visibility> sees;

    Audience(Set<Visibility> sees) {
        this.sees = sees;
    }

    public Set<Visibility> sees() {
        return Set.copyOf(sees);
    }

    public boolean sees(Visibility visibility) {
        return sees.contains(visibility);
    }
}
