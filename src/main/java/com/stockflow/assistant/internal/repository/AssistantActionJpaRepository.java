package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.assistant.internal.entity.AssistantActionJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AssistantActionJpaRepository extends JpaRepository<AssistantActionJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AssistantActionJpaEntity a WHERE a.id = :id AND a.ownerUserId = :owner")
    Optional<AssistantActionJpaEntity> findByIdForUpdate(@Param("id") UUID id, @Param("owner") UUID owner);

    Optional<AssistantActionJpaEntity> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    Page<AssistantActionJpaEntity> findByOwnerUserIdOrderByCreatedAtDescIdDesc(UUID ownerUserId, Pageable page);

    Page<AssistantActionJpaEntity> findByOwnerUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDescIdDesc(
            UUID ownerUserId, AssistantAction.Status status, Instant now, Pageable page);

    List<AssistantActionJpaEntity> findByMessageIdInAndOwnerUserId(Collection<UUID> messageIds, UUID ownerUserId);

    @Modifying
    @Query(value = """
            DELETE FROM assistant.action WHERE id IN (
                SELECT id FROM assistant.action
                WHERE conversation_id IS NULL AND created_at < :cutoff
                ORDER BY created_at
                LIMIT :limit)""", nativeQuery = true)
    int deleteStandaloneBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
