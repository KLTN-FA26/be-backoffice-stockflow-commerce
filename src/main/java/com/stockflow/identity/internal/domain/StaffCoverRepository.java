package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for {@link StaffCover}: {@code identity.staff_cover}. */
public interface StaffCoverRepository extends AggregateRepository<StaffCover, UUID> {

    /** The cover under a row lock held to the end of the transaction. */
    Optional<StaffCover> findForUpdate(UUID id);

    /** Whether another ACTIVE cover of {@code userId} shares a day with {@code from..to} (BR-S07). */
    boolean overlapsActive(UUID userId, LocalDate from, LocalDate to);

    /** The ACTIVE covers in force on {@code day} for each of {@code userIds} (at most one each), in one query. */
    List<StaffCover> activeOn(Collection<UUID> userIds, LocalDate day);

    /** The ACTIVE covers in which {@code coverUserId} stands in, under row locks. */
    List<StaffCover> activeStandingInForUpdate(UUID coverUserId);

    /**
     * Covers newest first, at most {@code limit}: of one absent person or everyone, and with {@code activeOn} only
     * those in force on that day or later (still to come or running).
     */
    List<StaffCover> list(UUID userId, LocalDate activeOn, int limit);

    /** {@link #list}, kept to the covers in which {@code involved} is the absent person or the stand-in. */
    List<StaffCover> listInvolving(UUID involved, UUID userId, LocalDate activeOn, int limit);
}
