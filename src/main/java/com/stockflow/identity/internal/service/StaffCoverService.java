package com.stockflow.identity.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffCovers;
import com.stockflow.identity.internal.domain.StaffCover;
import com.stockflow.identity.internal.domain.StaffCoverRepository;
import com.stockflow.identity.internal.repository.UserJpaRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Covers during leave (sales-owner plan BR-S07): arranging, cancelling and listing them, and {@link StaffCovers} for
 * the modules that ask who stands in today. The endpoints call this; the permission is theirs ({@code staff-covers}),
 * the rule of whose covers a caller may touch is here ({@code actsForAnyone}).
 *
 * <p>Who may stand in: a Sales agent ({@link SalesStaff#isActiveSalesAgent}), since the work routed to them is a
 * salesperson's. One cover at a time per absent person: checked first for a readable 409, then decided by the
 * exclusion constraint {@code ex_staff_cover_overlap} if two requests race.</p>
 */
@Service
public class StaffCoverService implements StaffCovers {

    /** The most covers a list returns: they are short-lived and few. */
    public static final int MAX_LISTED = 200;

    private final StaffCoverRepository covers;
    private final SalesStaff salesStaff;
    private final UserJpaRepository users;
    private final Clock clock;

    StaffCoverService(StaffCoverRepository covers, SalesStaff salesStaff, UserJpaRepository users, Clock clock) {
        this.covers = covers;
        this.salesStaff = salesStaff;
        this.users = users;
        this.clock = clock;
    }

    /** What a caller asks for: {@code coverUserId} stands in for {@code userId} from {@code from} to {@code to}. */
    public record ArrangeCover(UUID userId, UUID coverUserId, LocalDate from, LocalDate to, String reason) {
    }

    /** A cover as a screen shows it. */
    public record CoverView(UUID id, UUID userId, UUID coverUserId, LocalDate from, LocalDate to, String reason,
                            String status, UUID arrangedBy, Instant cancelledAt, UUID cancelledBy, String cancelReason,
                            long version) {
    }

    /**
     * Where a cover stands on the business day, for the covers screen (API contract BO-1): CANCELLED, UPCOMING (not
     * begun), ACTIVE (in force today) or ENDED (its last day passed). Only CANCELLED is stored; the rest is the date.
     */
    public enum Phase { ACTIVE, UPCOMING, ENDED, CANCELLED }

    /** A cover with the people named and its {@link Phase} as {@code status}. */
    public record CoverRow(UUID id, UUID userId, String userName, UUID coverUserId, String coverUserName,
                           LocalDate from, LocalDate to, String reason, Phase status, UUID arrangedBy,
                           String arrangedByName, Instant cancelledAt, String cancelReason, long version) {
    }

    /**
     * Arranges a cover.
     *
     * @param actorUserId   who asks
     * @param actsForAnyone whether the actor may arrange a cover for someone else (a manager: the caller passes
     *                      whether they hold {@code customer-sales-owners:UPDATE}); a Sales arranges only their own
     * @throws BusinessException {@code FORBIDDEN} for someone else's absence without {@code actsForAnyone};
     *         {@code USER_NOT_FOUND}; {@code SALES_OWNER_NOT_ASSIGNABLE} when the stand-in is not an active Sales agent;
     *         {@code STAFF_COVER_OVERLAP} and the rules of {@link StaffCover#arrange}
     */
    @Transactional
    @Auditable(action = AuditAction.CREATE, resourceType = "staff-cover", resourceId = "#result?.id()")
    public CoverView arrange(ArrangeCover command, UUID actorUserId, boolean actsForAnyone) {
        if (command.userId() == null || command.coverUserId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the absent person and the stand-in");
        }
        if (!actsForAnyone && !command.userId().equals(actorUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A salesperson arranges a cover for their own absence only");
        }
        if (!users.existsById(command.userId())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        StaffCover cover = StaffCover.arrange(Identifiers.newId(), command.userId(), command.coverUserId(),
                command.from(), command.to(), command.reason(), actorUserId, today());
        if (!salesStaff.lockActiveSalesAgent(command.coverUserId())) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "The stand-in must be an active salesperson");
        }
        if (covers.overlapsActive(command.userId(), command.from(), command.to())) {
            throw new BusinessException(ErrorCode.STAFF_COVER_OVERLAP);
        }
        try {
            return view(covers.save(cover));
        } catch (DataIntegrityViolationException race) {
            if (isExclusionViolation(race)) {
                throw new BusinessException(ErrorCode.STAFF_COVER_OVERLAP);
            }
            throw race;
        }
    }

    /**
     * Cancels a cover. Cancelling one already cancelled answers it as it is.
     *
     * @throws BusinessException {@code STAFF_COVER_NOT_FOUND}; {@code FORBIDDEN} for another person's cover without
     *         {@code actsForAnyone}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "staff-cover", resourceId = "#coverId")
    public CoverView cancel(UUID coverId, UUID actorUserId, boolean actsForAnyone) {
        StaffCover cover = covers.findForUpdate(coverId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STAFF_COVER_NOT_FOUND));
        if (!actsForAnyone && !cover.userId().equals(actorUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "A salesperson cancels only a cover of their own absence");
        }
        if (cover.cancel(actorUserId, clock.instant(), StaffCover.CancelReason.MANUAL)) {
            cover = covers.save(cover);
        }
        return view(cover);
    }

    /**
     * Covers newest first, at most {@link #MAX_LISTED}.
     *
     * @param userId     the absent person, or null for everyone
     * @param activeOnly only covers not cancelled that have not ended yet
     */
    @Transactional(readOnly = true)
    public List<CoverView> list(UUID userId, boolean activeOnly) {
        return covers.list(userId, activeOnly ? today() : null, MAX_LISTED).stream().map(StaffCoverService::view)
                .toList();
    }

    /**
     * {@link #list} as {@code viewer} may see it (finding S3): a manager ({@code seesEveryone}) every cover; anyone else
     * only the covers in which they are the absent person or the stand-in. A salesperson needs to know who stands in for
     * them and for whom they stand in - not their colleagues' leave, nor why.
     */
    @Transactional(readOnly = true)
    public List<CoverView> listFor(UUID viewer, boolean seesEveryone, UUID userId, boolean activeOnly) {
        if (seesEveryone) {
            return list(userId, activeOnly);
        }
        if (viewer == null) {
            return List.of();
        }
        return covers.listInvolving(viewer, userId, activeOnly ? today() : null, MAX_LISTED).stream()
                .map(StaffCoverService::view).toList();
    }

    /**
     * BR-S08: the account {@code coverUserId} stopped working, so no cover can rely on it any more. Joins the caller's
     * transaction (the status change), so a lock that rolls back cancels nothing.
     *
     * @return how many covers were cancelled
     */
    @Transactional
    public int cancelStandingIn(UUID coverUserId, UUID byUserId) {
        Instant now = clock.instant();
        int cancelled = 0;
        for (StaffCover cover : covers.activeStandingInForUpdate(coverUserId)) {
            if (cover.cancel(byUserId, now, StaffCover.CancelReason.COVER_DEACTIVATED)) {
                covers.save(cover);
                cancelled++;
            }
        }
        return cancelled;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ActiveCover> activeCovers(Collection<UUID> userIds, LocalDate day) {
        if (userIds == null || userIds.isEmpty() || day == null) {
            return Map.of();
        }
        Map<UUID, ActiveCover> found = new HashMap<>();
        for (StaffCover cover : covers.activeOn(userIds, day)) {
            found.put(cover.userId(), new ActiveCover(cover.id(), cover.userId(), cover.coverUserId(), cover.from(),
                    cover.to()));
        }
        return found;
    }

    /** {@link CoverView}s as rows: the people named in one query, the phase from today's date. */
    @Transactional(readOnly = true)
    public List<CoverRow> rows(List<CoverView> views) {
        return rows(views, null, true);
    }

    /**
     * {@link #rows(List)} for {@code viewer}: the {@code reason} of a cover (often why someone is away) only to the two
     * people it is about and to a manager ({@code seesEveryone}); left out for anyone else (finding S3).
     */
    @Transactional(readOnly = true)
    public List<CoverRow> rows(List<CoverView> views, UUID viewer, boolean seesEveryone) {
        java.util.Set<UUID> people = new java.util.HashSet<>();
        views.forEach(view -> {
            people.add(view.userId());
            people.add(view.coverUserId());
            people.add(view.arrangedBy());
        });
        people.remove(null);
        Map<UUID, String> names = new HashMap<>();
        if (!people.isEmpty()) {
            users.findAllById(people).forEach(user -> names.put(user.getId(),
                    user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername()
                            : user.getFullName()));
        }
        LocalDate today = today();
        return views.stream().map(view -> new CoverRow(view.id(), view.userId(), names.get(view.userId()),
                view.coverUserId(), names.get(view.coverUserId()), view.from(), view.to(),
                seesEveryone || (viewer != null && (viewer.equals(view.userId()) || viewer.equals(view.coverUserId())))
                        ? view.reason() : null,
                phase(view, today), view.arrangedBy(), names.get(view.arrangedBy()), view.cancelledAt(),
                view.cancelReason(), view.version())).toList();
    }

    private static Phase phase(CoverView view, LocalDate today) {
        if (StaffCover.Status.CANCELLED.name().equals(view.status())) {
            return Phase.CANCELLED;
        }
        if (today.isBefore(view.from())) {
            return Phase.UPCOMING;
        }
        return today.isAfter(view.to()) ? Phase.ENDED : Phase.ACTIVE;
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    private static CoverView view(StaffCover cover) {
        return new CoverView(cover.id(), cover.userId(), cover.coverUserId(), cover.from(), cover.to(), cover.reason(),
                cover.status().name(), cover.arrangedBy(), cover.cancelledAt(), cover.cancelledBy(),
                cover.cancelReason() == null ? null : cover.cancelReason().name(), cover.version());
    }

    /** SQLSTATE 23P01: the exclusion constraint {@code ex_staff_cover_overlap} refused a second cover on a day. */
    private static boolean isExclusionViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
