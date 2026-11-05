package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.assistant.internal.entity.AssistantKnowledgeChunkJpaEntity;
import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link KnowledgeRepository}: the sync through JPA, the search and the vectors in SQL (design §6.3–6.4).
 *
 * <p><b>Any word, not every word.</b> The question arrives as the model's search terms, several of which a rule may
 * not use; {@code websearch_to_tsquery} would require all of them and find nothing. The query is cut into words
 * ({@link KnowledgeSearch#words}: letters and digits only, so nothing reaches {@code to_tsquery} that it could parse as
 * an operator) and joined with OR; {@code ts_rank_cd} then ranks the chunks that match more of them higher.</p>
 *
 * <p><b>Vectors in plain SQL, never through JPA.</b> The entity does not map the embedding columns, so a sync that
 * rewrites a chunk leaves its old vector in place, and the vector's own {@code embedding_hash} — the content hash of
 * the text it was made from — tells it is stale: {@link #embedded} returns only vectors of the current text and model,
 * and {@link #saveEmbedding} stores a vector only while the chunk still has the text it was made from. A vector
 * travels as text ({@code {0.1,-0.2,…}}) cast to {@code real[]} by the database, the one array form every JDBC driver
 * passes the same way.</p>
 *
 * <p><b>Only this build's kinds are read.</b> A newer build may have written passages of a kind this one does not know
 * (the flows, before they were enabled here); read, they would fail every search they match. They are left alone:
 * the build that wrote them syncs them.</p>
 */
@Repository
class KnowledgeRepositoryAdapter implements KnowledgeRepository {

    private static final RowMapper<KnowledgeChunk> CHUNK = (rs, row) -> new KnowledgeChunk(rs.getString("source"),
            rs.getString("anchor"), KnowledgeChunk.Kind.valueOf(rs.getString("kind")), rs.getString("title"),
            rs.getString("body"));

    /** {@code kind} values this build reads ({@link KnowledgeChunk.Kind}). */
    private static final String[] KINDS = Arrays.stream(KnowledgeChunk.Kind.values()).map(Enum::name)
            .toArray(String[]::new);

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
    public List<KnowledgeChunk> search(String query, int limit, int minMatched) {
        Set<String> words = KnowledgeSearch.words(query);
        List<String> anchors = KnowledgeSearch.namedRules(query);
        if (words.isEmpty() && anchors.isEmpty()) {
            return List.of();
        }
        // Counted as the database compares them: "đơn" and "don" are one word, and must not make two matches.
        String[] each = words.stream().map(KnowledgeSearch::unmarked).distinct().toArray(String[]::new);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("words", words.isEmpty() ? "" : String.join(" | ", words))
                .addValue("text", String.join(" ", words))
                .addValue("each", each.length == 0 ? new String[] {""} : each)
                .addValue("needed", Math.max(1, Math.min(minMatched, each.length)))
                .addValue("anchors", anchors.isEmpty() ? new String[] {""} : anchors.toArray(String[]::new))
                .addValue("limit", limit)
                .addValue("kinds", KINDS);
        return jdbc.query("""
                WITH q AS (
                    SELECT CASE WHEN :words = '' THEN NULL
                                ELSE to_tsquery('simple', assistant.immutable_unaccent(:words)) END AS terms,
                           ARRAY(SELECT to_tsquery('simple', assistant.immutable_unaccent(w.word))
                                 FROM unnest(CAST(:each AS text[])) AS w(word) WHERE w.word <> '') AS each_term,
                           assistant.immutable_unaccent(:text) AS text)
                SELECT c.source, c.anchor, c.kind, c.title, c.body,
                       coalesce(ts_rank_cd(c.search_document, q.terms), 0)
                       + 0.5 * public.similarity(assistant.immutable_unaccent(lower(c.title)), q.text)
                       + CASE WHEN upper(c.anchor) = ANY(:anchors) THEN 10 ELSE 0 END AS score
                FROM assistant.knowledge_chunk c, q
                WHERE c.kind = ANY(:kinds)
                  AND ((q.terms IS NOT NULL AND c.search_document @@ q.terms
                        AND (SELECT count(*) FROM unnest(q.each_term) AS t(term) WHERE c.search_document @@ t.term)
                            >= :needed)
                   OR upper(c.anchor) = ANY(:anchors)
                   OR public.similarity(assistant.immutable_unaccent(lower(c.title)), q.text) > 0.3)
                ORDER BY score DESC, c.source, c.anchor
                LIMIT :limit""", parameters, CHUNK);
    }

    @Override
    public List<KnowledgeChunk> withoutEmbedding(String model, int dimensions) {
        return jdbc.query("""
                SELECT source, anchor, kind, title, body
                FROM assistant.knowledge_chunk
                WHERE kind = ANY(:kinds)
                  AND (embedding IS NULL OR embedding_model <> :model OR embedding_hash <> content_hash
                   OR cardinality(embedding) <> :dimensions)
                ORDER BY source, anchor""",
                new MapSqlParameterSource("model", model).addValue("dimensions", dimensions)
                        .addValue("kinds", KINDS), CHUNK);
    }

    @Override
    public boolean saveEmbedding(KnowledgeChunk chunk, String model, float[] embedding) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("embedding", literal(embedding))
                .addValue("model", model)
                .addValue("hash", chunk.contentHash())
                .addValue("source", chunk.source())
                .addValue("anchor", chunk.anchor());
        return jdbc.update("""
                UPDATE assistant.knowledge_chunk
                SET embedding = CAST(:embedding AS real[]), embedding_model = :model, embedding_hash = :hash
                WHERE source = :source AND anchor = :anchor AND content_hash = :hash""", parameters) == 1;
    }

    @Override
    public List<EmbeddedChunk> embedded(String model, int dimensions) {
        return jdbc.query("""
                SELECT source, anchor, kind, title, body, embedding
                FROM assistant.knowledge_chunk
                WHERE embedding_model = :model AND embedding_hash = content_hash
                  AND cardinality(embedding) = :dimensions AND kind = ANY(:kinds)""",
                new MapSqlParameterSource("model", model).addValue("dimensions", dimensions)
                        .addValue("kinds", KINDS),
                (rs, row) -> new EmbeddedChunk(CHUNK.mapRow(rs, row), floats(rs.getArray("embedding"))));
    }

    /** {@code {0.1,-0.25,1.0E-5}}: {@code Float.toString} for each value, which reads back as the same float. */
    static String literal(float[] embedding) {
        StringBuilder text = new StringBuilder(embedding.length * 12).append('{');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(embedding[i]);
        }
        return text.append('}').toString();
    }

    /** A {@code real[]} as the driver hands it over: boxed {@code Float}s, or a {@code float[]} where it can. */
    private static float[] floats(Array array) throws SQLException {
        try {
            Object values = array.getArray();
            if (values instanceof float[] primitive) {
                return primitive;
            }
            Object[] boxed = (Object[]) values;
            float[] floats = new float[boxed.length];
            for (int i = 0; i < boxed.length; i++) {
                floats[i] = ((Number) boxed[i]).floatValue();
            }
            return floats;
        } finally {
            array.free();
        }
    }
}
