package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.McpToken;
import com.stockflow.assistant.internal.domain.McpTokenRepository;
import org.springframework.data.domain.AuditorAware;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link McpTokenRepository} in SQL. A token is looked up by its hash under {@code uk_mcp_token_hash}, and the clock
 * that decides "live" is the caller's, the same one that set the expiry.
 */
@Repository
class McpTokenRepositoryAdapter implements McpTokenRepository {

    private static final RowMapper<McpToken> TOKEN = (rs, row) -> new McpToken(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("token_hint"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
            instant(rs.getTimestamp("last_used_at")), instant(rs.getTimestamp("revoked_at")));

    private static final String COLUMNS = "id, name, token_hint, created_at, expires_at, last_used_at, revoked_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditorAware<String> auditor;

    McpTokenRepositoryAdapter(NamedParameterJdbcTemplate jdbc, AuditorAware<String> auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    @Override
    public void lockOwner(UUID owner) {
        // One lock per user, released with the transaction.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('assistant.mcp_token:' || CAST(:owner AS text)))",
                new MapSqlParameterSource("owner", owner), rs -> null);
    }

    @Override
    public void insert(UUID id, UUID owner, String name, String hash, String hint, Instant createdAt,
                       Instant expiresAt) {
        jdbc.update("""
                INSERT INTO assistant.mcp_token (id, owner_user_id, name, token_hash, token_hint, expires_at,
                                                 created_at, created_by)
                VALUES (:id, :owner, :name, :hash, :hint, :expires, :created, :by)""",
                new MapSqlParameterSource("id", id).addValue("owner", owner).addValue("name", name)
                        .addValue("hash", hash).addValue("hint", hint)
                        .addValue("expires", Timestamp.from(expiresAt))
                        .addValue("created", Timestamp.from(createdAt)).addValue("by", by()));
    }

    @Override
    public List<McpToken> ownedBy(UUID owner, int limit) {
        // Live first: fifty rounds of making and revoking must not push a live token off its owner's list.
        return jdbc.query("SELECT " + COLUMNS + " FROM assistant.mcp_token WHERE owner_user_id = :owner"
                        + " ORDER BY (revoked_at IS NULL AND expires_at > now()) DESC, created_at DESC, id DESC"
                        + " LIMIT :limit",
                new MapSqlParameterSource("owner", owner).addValue("limit", limit), TOKEN);
    }

    @Override
    public int countLive(UUID owner, Instant now) {
        Integer live = jdbc.queryForObject("""
                SELECT count(*) FROM assistant.mcp_token
                WHERE owner_user_id = :owner AND revoked_at IS NULL AND expires_at > :now""",
                new MapSqlParameterSource("owner", owner).addValue("now", Timestamp.from(now)), Integer.class);
        return live == null ? 0 : live;
    }

    @Override
    public Optional<McpToken> revoke(UUID id, UUID owner, Instant at) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("id", id).addValue("owner", owner)
                .addValue("at", Timestamp.from(at)).addValue("by", by());
        jdbc.update("""
                UPDATE assistant.mcp_token
                SET revoked_at = :at, last_modified_at = :at, last_modified_by = :by, version = version + 1
                WHERE id = :id AND owner_user_id = :owner AND revoked_at IS NULL""", parameters);
        return jdbc.query("SELECT " + COLUMNS + " FROM assistant.mcp_token WHERE id = :id AND owner_user_id = :owner",
                parameters, TOKEN).stream().findFirst();
    }

    @Override
    public int revokeAllOf(UUID owner, Instant at) {
        return jdbc.update("""
                UPDATE assistant.mcp_token
                SET revoked_at = :at, last_modified_at = :at, last_modified_by = :by, version = version + 1
                WHERE owner_user_id = :owner AND revoked_at IS NULL AND expires_at > :at""",
                new MapSqlParameterSource("owner", owner).addValue("at", Timestamp.from(at)).addValue("by", by()));
    }

    @Override
    public Optional<Holder> live(String hash, Instant now) {
        return jdbc.query("""
                SELECT id, owner_user_id FROM assistant.mcp_token
                WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now""",
                new MapSqlParameterSource("hash", hash).addValue("now", Timestamp.from(now)),
                (rs, row) -> new Holder(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class)))
                .stream().findFirst();
    }

    @Override
    public void used(UUID id, Instant at) {
        // Not a change its owner made: the audit columns and the version stay as they were.
        jdbc.update("""
                UPDATE assistant.mcp_token SET last_used_at = :at
                WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :since)""",
                new MapSqlParameterSource("id", id).addValue("at", Timestamp.from(at))
                        .addValue("since", Timestamp.from(at.minusSeconds(60))));
    }

    @Override
    public int deleteDeadBefore(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM assistant.mcp_token WHERE id IN (
                    SELECT id FROM assistant.mcp_token
                    WHERE expires_at < :cutoff OR revoked_at < :cutoff
                    ORDER BY id LIMIT :limit)""",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)).addValue("limit", limit));
    }

    private String by() {
        return auditor.getCurrentAuditor().orElse(null);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
