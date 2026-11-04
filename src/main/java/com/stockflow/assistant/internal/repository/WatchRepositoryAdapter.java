package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.WatchRepository;
import com.stockflow.common.id.Identifiers;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WatchRepository} in SQL. Each item is one upsert on {@code uk_watch_item} that says itself whether the row was
 * added ({@code xmax = 0} on the row RETURNING gives back): "new since the last look" is decided by the database in the
 * statement that records it, not by a read and a write that another look could interleave.
 *
 * <p>{@link #analysed} names a conversation the same transaction has just opened through JPA, whose INSERT Hibernate
 * holds until its next flush; a JDBC statement does not flush, so it flushes first ({@code fk_watch_item_conversation}
 * is not deferrable).</p>
 */
@Repository
class WatchRepositoryAdapter implements WatchRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditorAware<String> auditor;
    private final EntityManager entityManager;

    WatchRepositoryAdapter(NamedParameterJdbcTemplate jdbc, AuditorAware<String> auditor,
                           EntityManager entityManager) {
        this.jdbc = jdbc;
        this.auditor = auditor;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<State> state(UUID ownerUserId, String section) {
        return jdbc.query("""
                SELECT view, started_at, last_looked_at, partial_since FROM assistant.watch_state
                WHERE owner_user_id = :owner AND section = :section""", owned(ownerUserId).addValue("section", section),
                (rs, row) -> new State(rs.getString("view"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("last_looked_at").toInstant(),
                        rs.getTimestamp("partial_since") == null ? null
                                : rs.getTimestamp("partial_since").toInstant())).stream().findFirst();
    }

    @Override
    public void restart(UUID ownerUserId, String section, String view, Instant at) {
        MapSqlParameterSource parameters = owned(ownerUserId).addValue("section", section).addValue("view", view)
                .addValue("at", Timestamp.from(at)).addValue("by", by());
        jdbc.update("DELETE FROM assistant.watch_item WHERE owner_user_id = :owner AND section = :section", parameters);
        jdbc.update("""
                INSERT INTO assistant.watch_state (owner_user_id, section, view, started_at, last_looked_at,
                                                   created_at, created_by)
                VALUES (:owner, :section, :view, :at, :at, :at, :by)
                ON CONFLICT (owner_user_id, section) DO UPDATE
                    SET view = EXCLUDED.view, started_at = EXCLUDED.started_at,
                        last_looked_at = EXCLUDED.last_looked_at, partial_since = NULL,
                        last_modified_at = EXCLUDED.started_at, last_modified_by = EXCLUDED.created_by""", parameters);
    }

    @Override
    public void looked(UUID ownerUserId, String section, Instant at, Instant partialSince) {
        jdbc.update("""
                UPDATE assistant.watch_state SET last_looked_at = :at, partial_since = :partial,
                                                 last_modified_at = :at, last_modified_by = :by
                WHERE owner_user_id = :owner AND section = :section""", owned(ownerUserId).addValue("section", section)
                .addValue("at", Timestamp.from(at))
                .addValue("partial", partialSince == null ? null : Timestamp.from(partialSince))
                .addValue("by", by()));
    }

    @Override
    public void drop(UUID ownerUserId, String section) {
        jdbc.update("DELETE FROM assistant.watch_state WHERE owner_user_id = :owner AND section = :section",
                owned(ownerUserId).addValue("section", section));
    }

    @Override
    public List<SeenItem> see(UUID ownerUserId, String section, Collection<String> keys, Instant at) {
        List<SeenItem> added = new ArrayList<>();
        for (String key : new LinkedHashSet<>(keys)) {
            UUID id = Identifiers.newId();
            Boolean inserted = jdbc.queryForObject("""
                    INSERT INTO assistant.watch_item (id, owner_user_id, section, item_key, first_seen_at, last_seen_at,
                                                      created_at, created_by)
                    VALUES (:id, :owner, :section, :key, :at, :at, :at, :by)
                    ON CONFLICT (owner_user_id, section, item_key)
                        DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at
                    RETURNING (xmax = 0)""", owned(ownerUserId)
                    .addValue("id", id)
                    .addValue("section", section)
                    .addValue("key", key)
                    .addValue("at", Timestamp.from(at))
                    .addValue("by", by()), Boolean.class);
            if (Boolean.TRUE.equals(inserted)) {
                added.add(new SeenItem(id, section, key, at, false));
            }
        }
        return added;
    }

    @Override
    public int forgetUnseen(UUID ownerUserId, String section, Instant lookedAt) {
        return jdbc.update("""
                DELETE FROM assistant.watch_item
                WHERE owner_user_id = :owner AND section = :section AND last_seen_at < :at""",
                owned(ownerUserId).addValue("section", section).addValue("at", Timestamp.from(lookedAt)));
    }

    @Override
    public List<SeenItem> pending(UUID ownerUserId) {
        return jdbc.query("""
                SELECT id, section, item_key, first_seen_at, analysed_at FROM assistant.watch_item
                WHERE owner_user_id = :owner AND notified_at IS NULL
                ORDER BY section, first_seen_at, item_key""", owned(ownerUserId),
                (rs, row) -> new SeenItem(rs.getObject("id", UUID.class), rs.getString("section"),
                        rs.getString("item_key"), rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("analysed_at") != null));
    }

    @Override
    public void dealtWith(Collection<UUID> itemIds, Instant at) {
        if (itemIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE assistant.watch_item SET notified_at = :at, last_modified_at = :at, last_modified_by = :by,
                                                version = version + 1
                WHERE id IN (:ids) AND notified_at IS NULL""", new MapSqlParameterSource()
                .addValue("ids", List.copyOf(itemIds)).addValue("at", Timestamp.from(at)).addValue("by", by()));
    }

    @Override
    public void analysed(Collection<UUID> itemIds, UUID conversationId, Instant at) {
        if (itemIds.isEmpty()) {
            return;
        }
        entityManager.flush();
        jdbc.update("""
                UPDATE assistant.watch_item SET analysed_at = :at, conversation_id = :conversation,
                                                last_modified_at = :at, last_modified_by = :by, version = version + 1
                WHERE id IN (:ids)""", new MapSqlParameterSource().addValue("ids", List.copyOf(itemIds))
                .addValue("conversation", conversationId).addValue("at", Timestamp.from(at)).addValue("by", by()));
    }

    @Override
    public void notAnalysed(Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE assistant.watch_item SET analysed_at = NULL, conversation_id = NULL, version = version + 1
                WHERE id IN (:ids)""", new MapSqlParameterSource().addValue("ids", List.copyOf(itemIds)));
    }

    @Override
    public int deleteNotLookedAtSince(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM assistant.watch_state WHERE (owner_user_id, section) IN (
                    SELECT owner_user_id, section FROM assistant.watch_state
                    WHERE last_looked_at < :cutoff ORDER BY last_looked_at LIMIT :limit)""",
                new MapSqlParameterSource().addValue("cutoff", Timestamp.from(cutoff)).addValue("limit", limit));
    }

    private static MapSqlParameterSource owned(UUID ownerUserId) {
        return new MapSqlParameterSource().addValue("owner", ownerUserId);
    }

    private String by() {
        return auditor.getCurrentAuditor().orElse(null);
    }
}
