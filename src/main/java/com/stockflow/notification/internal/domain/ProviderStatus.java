package com.stockflow.notification.internal.domain;

import java.time.Instant;

/**
 * What the provider reported about a message after accepting it. Mapped {@code EnumType.STRING} onto
 * {@code notification.delivery_log.provider_status}, whose CHECK lists the same values.
 *
 * <p>Reports arrive out of order and more than once (webhooks are retried), so a report replaces the stored one only
 * when it says more: a delay never overwrites a delivery, a delivery never overwrites a bounce or a complaint (a
 * complaint comes after the delivery it complains about). Between two final reports the later one wins.</p>
 */
public enum ProviderStatus {

    DELIVERY_DELAYED(1),
    DELIVERED(2),
    BOUNCED(3),
    COMPLAINED(3),
    FAILED(3);

    private final int finality;

    ProviderStatus(int finality) {
        this.finality = finality;
    }

    /** Whether this report, made at {@code at}, should replace {@code current} made at {@code currentAt}. */
    public boolean replaces(ProviderStatus current, Instant currentAt, Instant at) {
        if (current == null) {
            return true;
        }
        if (finality != current.finality) {
            return finality > current.finality;
        }
        return currentAt == null || (at != null && at.isAfter(currentAt));
    }
}
