package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.AssistantAction;
import com.stockflow.assistant.internal.domain.AssistantActionRepository;
import com.stockflow.assistant.internal.entity.AssistantActionJpaEntity;
import com.stockflow.common.api.PageResponse;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AssistantActionRepository}. Every read is by owner: the data scope of the endpoint that confirms a purchase
 * order is the purchase-order screen's, which may be every warehouse, and must never make another user's suggestion
 * theirs to confirm.
 *
 * <p><b>Flushed first.</b> An answer's suggestions are written in the transaction that stores the answer, and name
 * its message ({@code fk_action_message}, not deferrable). The message is a new entity with an assigned id: Hibernate
 * queues its INSERT until the next flush, and neither a JDBC statement nor {@code hibernate.order_inserts} (which may
 * batch inserts by entity) can be relied on to send it first. {@link #suggestAll} and {@link #supersede} flush the
 * persistence context before they write.</p>
 */
@Repository
class AssistantActionRepositoryAdapter implements AssistantActionRepository {

    private final AssistantActionJpaRepository actions;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final AuditorAware<String> auditor;

    AssistantActionRepositoryAdapter(AssistantActionJpaRepository actions, NamedParameterJdbcTemplate jdbc,
                                     EntityManager entityManager, AuditorAware<String> auditor) {
        this.actions = actions;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.auditor = auditor;
    }

    @Override
    public AssistantAction save(AssistantAction action) {
        Optional<AssistantActionJpaEntity> stored = actions.findById(action.id());
        if (stored.isPresent()) {
            stored.get().decide(action);
            return stored.get().toDomain();
        }
        AssistantActionJpaEntity created = actions.save(new AssistantActionJpaEntity(action));
        return created.toDomain();
    }

    @Override
    public void suggestAll(List<AssistantAction> suggestions) {
        entityManager.flush();
        for (AssistantAction action : suggestions) {
            actions.save(new AssistantActionJpaEntity(action));
        }
        entityManager.flush();
    }

    /**
     * One UPDATE, so a confirmation holding a row's lock is waited for and its CONFIRMED is re-read by the
     * {@code status} predicate (READ COMMITTED): a confirmed suggestion is never superseded. The lines' SKUs are
     * compared upper case, as the confirmation compares them. {@code last_modified_by} is the auditor the JPA writes
     * use.
     */
    @Override
    public int supersedeCovered(UUID ownerUserId, AssistantAction.Kind kind, String proposalKey,
                                Collection<String> skus, Instant now) {
        if (skus.isEmpty()) {
            return 0;
        }
        entityManager.flush();
        return jdbc.update("""
                UPDATE assistant.action a
                SET status = 'SUPERSEDED', decided_at = :now, last_modified_at = :now, last_modified_by = :by,
                    version = a.version + 1
                WHERE a.owner_user_id = :owner AND a.kind = :kind AND a.proposal_key = :key
                  AND a.status = 'SUGGESTED' AND a.expires_at > :now
                  AND (SELECT coalesce(jsonb_agg(upper(line ->> 'sku')), '[]'::jsonb)
                       FROM jsonb_array_elements(a.suggested -> 'lines') line)
                      <@ to_jsonb(ARRAY[:skus]::text[])""", new MapSqlParameterSource()
                .addValue("owner", ownerUserId)
                .addValue("kind", kind.name())
                .addValue("key", proposalKey)
                .addValue("skus", List.copyOf(skus))
                .addValue("by", auditor.getCurrentAuditor().orElse(null))
                .addValue("now", Timestamp.from(now)));
    }

    @Override
    public boolean confirmedSince(UUID ownerUserId, AssistantAction.Kind kind, String proposalKey, Instant since,
                                  UUID excluding) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assistant.action
                               WHERE owner_user_id = :owner AND kind = :kind AND proposal_key = :key
                                 AND status = 'CONFIRMED' AND decided_at >= :since AND id <> :excluding)""",
                new MapSqlParameterSource()
                        .addValue("owner", ownerUserId)
                        .addValue("kind", kind.name())
                        .addValue("key", proposalKey)
                        .addValue("since", Timestamp.from(since))
                        .addValue("excluding", excluding), Boolean.class));
    }

    @Override
    public Optional<AssistantAction> findForUpdate(UUID actionId, UUID ownerUserId) {
        Optional<AssistantActionJpaEntity> locked = actions.findByIdForUpdate(actionId, ownerUserId);
        // The lock is taken on the row; the copy may be one this persistence context read before it. Re-read it.
        locked.ifPresent(entityManager::refresh);
        return locked.map(AssistantActionJpaEntity::toDomain);
    }

    @Override
    public Optional<AssistantAction> find(UUID actionId, UUID ownerUserId) {
        return actions.findByIdAndOwnerUserId(actionId, ownerUserId).map(AssistantActionJpaEntity::toDomain);
    }

    @Override
    public PageResponse<AssistantAction> mine(UUID ownerUserId, boolean openOnly, Instant now, int page, int size) {
        PageRequest request = PageRequest.of(page, size);
        Page<AssistantActionJpaEntity> found = openOnly
                ? actions.findByOwnerUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDescIdDesc(ownerUserId,
                        AssistantAction.Status.SUGGESTED, now, request)
                : actions.findByOwnerUserIdOrderByCreatedAtDescIdDesc(ownerUserId, request);
        return PageResponse.of(found.getContent().stream().map(AssistantActionJpaEntity::toDomain).toList(),
                found.getNumber(), found.getSize(), found.getTotalElements());
    }

    @Override
    public List<AssistantAction> ofMessages(Collection<UUID> messageIds, UUID ownerUserId) {
        if (messageIds.isEmpty()) {
            return List.of();
        }
        return actions.findByMessageIdInAndOwnerUserId(messageIds, ownerUserId).stream()
                .map(AssistantActionJpaEntity::toDomain).toList();
    }

    @Override
    public int deleteStandaloneBefore(Instant cutoff, int limit) {
        return actions.deleteStandaloneBefore(cutoff, limit);
    }
}
