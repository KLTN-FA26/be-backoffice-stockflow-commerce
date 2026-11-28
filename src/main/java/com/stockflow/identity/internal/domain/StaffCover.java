package com.stockflow.identity.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * "{@code coverUserId} looks after {@code userId}'s work from {@code from} to {@code to}" (sales-owner plan BR-S07).
 *
 * <p>A cover never changes who owns a customer: it only redirects, for its days, who is responsible
 * ({@code ResponsibleSales} answers COVER). It ends by itself after its last day, so nothing has to be undone, and a
 * cover is never edited - a wrong one is cancelled and arranged again, which keeps the trail of who was told what.</p>
 *
 * <p>Its rules are stated twice: here, and as the CHECKs and the exclusion constraint of {@code identity.staff_cover}
 * (V20270128000110). That one person has at most one cover on a day depends on the other covers, so the aggregate
 * cannot see it: the service checks it first for a readable answer and the exclusion constraint decides a race.</p>
 */
public final class StaffCover extends AggregateRoot {

    /** D7: at most 60 days, counted inclusively. */
    public static final int MAX_DAYS = 60;

    public enum Status { ACTIVE, CANCELLED }

    /** Why a cover was cancelled: by a person, or because its stand-in's account stopped working (BR-S08). */
    public enum CancelReason { MANUAL, COVER_DEACTIVATED }

    private final UUID id;
    private final UUID userId;
    private final UUID coverUserId;
    private final LocalDate from;
    private final LocalDate to;
    private final String reason;
    private final UUID arrangedBy;
    private Status status;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private CancelReason cancelReason;
    private final long version;

    public StaffCover(UUID id, UUID userId, UUID coverUserId, LocalDate from, LocalDate to, String reason,
                      UUID arrangedBy, Status status, Instant cancelledAt, UUID cancelledBy, CancelReason cancelReason,
                      long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.coverUserId = Objects.requireNonNull(coverUserId, "coverUserId");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.reason = reason == null || reason.isBlank() ? null : reason.strip();
        this.arrangedBy = arrangedBy;
        this.status = Objects.requireNonNull(status, "status");
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancelReason = cancelReason;
        this.version = version;
    }

    /**
     * A new cover.
     *
     * @param today the business day: a cover that has already ended is refused, it would cover nothing
     * @throws BusinessException {@code STAFF_COVER_SELF}; {@code VALIDATION_FAILED} when {@code from} is after
     *         {@code to} or {@code to} is before {@code today}; {@code STAFF_COVER_TOO_LONG} past {@link #MAX_DAYS}
     */
    public static StaffCover arrange(UUID id, UUID userId, UUID coverUserId, LocalDate from, LocalDate to,
                                     String reason, UUID arrangedBy, LocalDate today) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(coverUserId, "coverUserId");
        if (userId.equals(coverUserId)) {
            throw new BusinessException(ErrorCode.STAFF_COVER_SELF);
        }
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cover's first day comes before its last");
        }
        if (to.isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cover that has already ended covers nothing");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException(ErrorCode.STAFF_COVER_TOO_LONG);
        }
        if (reason != null && reason.strip().length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A cover's reason is at most 500 characters");
        }
        return new StaffCover(id, userId, coverUserId, from, to, reason, arrangedBy, Status.ACTIVE, null, null, null,
                0L);
    }

    /** In force on {@code day}: not cancelled, and the day is one of its days. */
    public boolean coversOn(LocalDate day) {
        return status == Status.ACTIVE && !day.isBefore(from) && !day.isAfter(to);
    }

    /**
     * Cancels it. Cancelling a cancelled cover changes nothing, so a retry or a second deactivation is harmless.
     *
     * @return whether this call cancelled it
     */
    public boolean cancel(UUID by, Instant at, CancelReason why) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(why, "why");
        if (status == Status.CANCELLED) {
            return false;
        }
        status = Status.CANCELLED;
        cancelledAt = at;
        cancelledBy = by;
        cancelReason = why;
        return true;
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public UUID coverUserId() { return coverUserId; }
    public LocalDate from() { return from; }
    public LocalDate to() { return to; }
    public String reason() { return reason; }
    public UUID arrangedBy() { return arrangedBy; }
    public Status status() { return status; }
    public Instant cancelledAt() { return cancelledAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public CancelReason cancelReason() { return cancelReason; }
    public long version() { return version; }
}
