package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.entity.AssistantKnowledgeChunkJpaEntity;
import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link KnowledgeRepository}: the sync through JPA, the search in SQL (design §6.3).
 *
 * <p><b>Any word, not every word.</b> The question arrives as the model's search terms, several of which a rule may
 * not use; {@code websearch_to_tsquery} would require all of them and find nothing. The query is cut into words here
 * (letters and digits only, so nothing reaches {@code to_tsquery} that it could parse as an operator) and joined with
 * OR; {@code ts_rank_cd} then ranks the chunks that match more of them higher.</p>
 */
@Repository
class KnowledgeRepositoryAdapter implements KnowledgeRepository {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");
    private static final Pattern ANCHOR = Pattern.compile("BR-[A-Z]+-\\d+[a-z]?", Pattern.CASE_INSENSITIVE);
    private static final int MAX_WORDS = 16;

    private final AssistantKnowledgeChunkJpaRepository chunks;
    private final NamedParameterJdbcTemplate jdbc;

    KnowledgeRepositoryAdapter(AssistantKnowledgeChunkJpaRepository chunks, NamedParameterJdbcTemplate jdbc) {
        this.chunks = chunks;
        this.jdbc = jdbc;
    }

    @Override
    public Sync sync(String source, List<KnowledgeChunk> wanted, Instant builtAt) {
        // Serialises two instances starting together; released at commit.
        jdbc.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(hashtext('assistant.knowledge'))",
                Object.class);
        MapSqlParameterSource keys = new MapSqlParameterSource("source", source)
                .addValue("builtAt", builtAt == null ? null : Timestamp.from(builtAt), Types.TIMESTAMP);
        List<Timestamp> indexedBy = jdbc.query(
                "SELECT built_at FROM assistant.knowledge_source WHERE source = :source", keys,
                (rs, row) -> rs.getTimestamp("built_at"));
        if (builtAt != null && !indexedBy.isEmpty() && indexedBy.getFirst() != null
                && indexedBy.getFirst().toInstant().isAfter(builtAt)) {
            return new Sync(0, true);
        }
        Map<String, AssistantKnowledgeChunkJpaEntity> stored = new HashMap<>();
        chunks.findBySource(source).forEach(row -> stored.put(row.getAnchor(), row));
        int changes = 0;
        Set<String> kept = new LinkedHashSet<>();
        for (KnowledgeChunk chunk : wanted) {
            kept.add(chunk.anchor());
            AssistantKnowledgeChunkJpaEntity row = stored.get(chunk.anchor());
            if (row == null) {
                chunks.save(new AssistantKnowledgeChunkJpaEntity(Identifiers.newId(), chunk));
                changes++;
            } else if (!row.getContentHash().equals(chunk.contentHash())) {
                row.rewrite(chunk);
                changes++;
            }
        }
        for (AssistantKnowledgeChunkJpaEntity row : stored.values()) {
            if (!kept.contains(row.getAnchor())) {
                chunks.delete(row);
                changes++;
            }
        }
        jdbc.update("""
                INSERT INTO assistant.knowledge_source (source, built_at, synced_at) VALUES (:source, :builtAt, now())
                ON CONFLICT (source) DO UPDATE SET built_at = EXCLUDED.built_at, synced_at = EXCLUDED.synced_at""",
                keys);
        return new Sync(changes, false);
    }

    @Override
    public List<KnowledgeChunk> search(String query, int limit) {
        Set<String> words = new LinkedHashSet<>();
        Matcher word = WORD.matcher(Normalizer.normalize(query, Normalizer.Form.NFC));
        while (word.find() && words.size() < MAX_WORDS) {
            words.add(word.group().toLowerCase(Locale.ROOT));
        }
        List<String> anchors = new ArrayList<>();
        Matcher anchor = ANCHOR.matcher(query);
        while (anchor.find()) {
            anchors.add(anchor.group().toUpperCase(Locale.ROOT));
        }
        if (words.isEmpty() && anchors.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("words", words.isEmpty() ? "" : String.join(" | ", words))
                .addValue("text", String.join(" ", words))
                .addValue("anchors", anchors.isEmpty() ? new String[] {""} : anchors.toArray(String[]::new))
                .addValue("limit", limit);
        return jdbc.query("""
                WITH q AS (
                    SELECT CASE WHEN :words = '' THEN NULL
                                ELSE to_tsquery('simple', assistant.immutable_unaccent(:words)) END AS terms,
                           assistant.immutable_unaccent(:text) AS text)
                SELECT c.source, c.anchor, c.kind, c.title, c.body,
                       coalesce(ts_rank_cd(c.search_document, q.terms), 0)
                       + 0.5 * public.similarity(assistant.immutable_unaccent(lower(c.title)), q.text)
                       + CASE WHEN upper(c.anchor) = ANY(:anchors) THEN 10 ELSE 0 END AS score
                FROM assistant.knowledge_chunk c, q
                WHERE (q.terms IS NOT NULL AND c.search_document @@ q.terms)
                   OR upper(c.anchor) = ANY(:anchors)
                   OR public.similarity(assistant.immutable_unaccent(lower(c.title)), q.text) > 0.3
                ORDER BY score DESC, c.source, c.anchor
                LIMIT :limit""", parameters, (rs, row) -> new KnowledgeChunk(rs.getString("source"),
                rs.getString("anchor"), KnowledgeChunk.Kind.valueOf(rs.getString("kind")), rs.getString("title"),
                rs.getString("body")));
    }
}
