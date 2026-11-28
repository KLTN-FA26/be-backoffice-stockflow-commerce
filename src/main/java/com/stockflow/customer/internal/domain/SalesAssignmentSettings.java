package com.stockflow.customer.internal.domain;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port for a salesperson's assignment settings ({@code customer.sales_assignment_setting}; plan §10.4, D13): paused for
 * new customers and leads, and how many new customers a month. A salesperson with no row has the defaults.
 */
public interface SalesAssignmentSettings {

    /** @param capacityPerMonth null for no limit; {@code version} is -1 while no row exists */
    record Setting(UUID userId, boolean paused, Integer capacityPerMonth, long version) {

        public static Setting defaults(UUID userId) {
            return new Setting(userId, false, null, -1L);
        }
    }

    /** The setting of each of {@code userIds}, the defaults for those without a row. One query. */
    Map<UUID, Setting> of(Collection<UUID> userIds);

    /** The user's setting under a row lock (the defaults, unlocked, when there is no row yet). */
    Setting forUpdate(UUID userId);

    /** Writes the setting (insert or update), in the caller's transaction. */
    Setting save(UUID userId, boolean paused, Integer capacityPerMonth, UUID by);
}
