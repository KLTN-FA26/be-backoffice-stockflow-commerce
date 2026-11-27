package com.stockflow.identity.internal.repository;

import com.stockflow.identity.internal.domain.StaffCover;
import com.stockflow.identity.internal.domain.StaffCoverRepository;
import com.stockflow.identity.internal.entity.StaffCoverJpaEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@link StaffCoverRepository} over {@code identity.staff_cover}; maps the entity to the aggregate and back. */
@Repository
class StaffCoverRepositoryAdapter implements StaffCoverRepository {

    private final StaffCoverJpaRepository covers;

    StaffCoverRepositoryAdapter(StaffCoverJpaRepository covers) {
        this.covers = covers;
    }

    @Override
    public Optional<StaffCover> findById(UUID id) {
        return covers.findById(id).map(StaffCoverRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<StaffCover> findForUpdate(UUID id) {
        return covers.lockById(id).map(StaffCoverRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return covers.existsById(id);
    }

    @Override
    public StaffCover save(StaffCover cover) {
        StaffCoverJpaEntity row = covers.findById(cover.id()).orElseGet(() -> new StaffCoverJpaEntity(cover.id(),
                cover.userId(), cover.coverUserId(), cover.from(), cover.to(), cover.reason(), cover.arrangedBy(),
                cover.status().name()));
        row.applyStatus(cover.status().name(), cover.cancelledAt(), cover.cancelledBy(),
                cover.cancelReason() == null ? null : cover.cancelReason().name());
        return toDomain(covers.saveAndFlush(row));
    }

    @Override
    public boolean overlapsActive(UUID userId, LocalDate from, LocalDate to) {
        return covers.overlapsActive(userId, from, to);
    }

    @Override
    public List<StaffCover> activeOn(Collection<UUID> userIds, LocalDate day) {
        List<UUID> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return covers.findActiveOn(ids, day).stream().map(StaffCoverRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<StaffCover> activeStandingInForUpdate(UUID coverUserId) {
        return covers.lockActiveStandingIn(coverUserId).stream().map(StaffCoverRepositoryAdapter::toDomain).toList();
    }

    @Override
    public List<StaffCover> list(UUID userId, LocalDate activeOn, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, limit));
        // A placeholder id when everyone is asked: the parameter is never compared then, but it must have a type.
        UUID who = userId == null ? new UUID(0, 0) : userId;
        List<StaffCoverJpaEntity> rows = activeOn == null ? covers.list(userId == null, who, page)
                : covers.listActive(userId == null, who, activeOn, page);
        return rows.stream().map(StaffCoverRepositoryAdapter::toDomain).toList();
    }

    private static StaffCover toDomain(StaffCoverJpaEntity row) {
        return new StaffCover(row.getId(), row.getUserId(), row.getCoverUserId(), row.getFromDate(), row.getToDate(),
                row.getReason(), row.getArrangedBy(), StaffCover.Status.valueOf(row.getStatus()), row.getCancelledAt(),
                row.getCancelledBy(),
                row.getCancelReason() == null ? null : StaffCover.CancelReason.valueOf(row.getCancelReason()),
                row.getVersion());
    }
}
