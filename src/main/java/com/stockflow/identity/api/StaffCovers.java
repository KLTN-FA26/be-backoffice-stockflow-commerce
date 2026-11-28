package com.stockflow.identity.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The covers in force (sales-owner plan BR-S07): who stands in for an absent salesperson on a day. Read by
 * {@code customer}'s {@code ResponsibleSales}; written only inside identity.
 */
public interface StaffCovers {

    /**
     * For each of {@code userIds} that has one, the cover in force on {@code day} whose stand-in is still ACTIVE. One
     * query whatever the number of ids; ids with no cover are left out (never null values).
     */
    Map<UUID, ActiveCover> activeCovers(Collection<UUID> userIds, LocalDate day);

    /**
     * A cover in force: {@code coverUserId} stands in for {@code userId} from {@code from} to {@code to}, both included.
     */
    record ActiveCover(UUID coverId, UUID userId, UUID coverUserId, LocalDate from, LocalDate to) {
    }
}
