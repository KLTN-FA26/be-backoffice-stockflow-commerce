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
 * an operator) and joined with OR.</p>
 *
 * <p><b>Ranked by how much of the query a passage holds, not how often.</b> A passage scores one for each distinct
 * word of the query it holds, {@value #PAIR_WEIGHT} for each pair of adjacent words it holds as a phrase
 * ({@link KnowledgeSearch#pairs}), {@value #TITLE_WEIGHT} for each of those words in its anchor or title,
 * {@code ts_rank_cd} normalised below one (closeness), half the title's trigram similarity, and a hundred when the
 * query names it, more than everything else together. The analysis is Vietnamese, a word is often two syllables, and
 * the search counts syllables: ranked by {@code ts_rank_cd} alone, a long flow repeating "hàng", "kho" and "chuyến"
 * outranked the one rule holding "chuyển hàng giữa các kho" as written. Weighing the words by their rarity (BM25's
 * inverse document frequency), with or without its length normalisation, was measured and found less (design
 * §6.3).</p>
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

    /**
     * What each pair of the query's words held as a phrase adds to a passage's score ({@link KnowledgeSearch#pairs}),
     * where each of its distinct words adds one: a passage holding "đổi trả" and "lỗi sản xuất" as written comes before
     * one holding the same syllables scattered. Chosen on {@code RetrievalEval}'s keyword-only numbers (design §6.3).
     */
    static final double PAIR_WEIGHT = 1.0;

    /**
     * What each distinct word of the query in a passage's anchor or title adds besides: a glossary term titled
     * "Reservation / Giữ hàng" is about the words it is named by, where a long flow only mentions them.
     */
    static final double TITLE_WEIGHT = 0.5;

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
    public List<String> sources() {
        return jdbc.queryForList("SELECT DISTINCT source FROM assistant.knowledge_chunk ORDER BY source",
                new MapSqlParameterSource(), String.class);
    }

    @Override
    public List<KnowledgeChunk> rules(java.util.Collection<String> anchors) {
        if (anchors.isEmpty()) {
            return List.of();
        }
        // A rule cut into passages is "15 BR-08", "15 BR-08 (2)", ...: the parts are found by the anchor's prefix.
        return jdbc.query("""
                        SELECT source, anchor, kind, title, body FROM assistant.knowledge_chunk
                        WHERE kind = 'RULE' AND (anchor = ANY(:anchors) OR anchor LIKE ANY(:parts))
                        ORDER BY anchor""",
                new MapSqlParameterSource("anchors", anchors.toArray(String[]::new))
                        .addValue("parts", anchors.stream().map(anchor -> anchor + " (%").toArray(String[]::new)),
                CHUNK);
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
        String[] pairs = KnowledgeSearch.pairs(query).stream().map(KnowledgeSearch::unmarked).distinct()
                .toArray(String[]::new);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("words", words.isEmpty() ? "" : String.join(" | ", words))
                .addValue("text", String.join(" ", words))
                .addValue("each", each.length == 0 ? new String[] {""} : each)
                .addValue("needed", Math.max(1, Math.min(minMatched, each.length)))
                .addValue("pairs", pairs.length == 0 ? new String[] {""} : pairs)
                .addValue("pairWeight", PAIR_WEIGHT)
                .addValue("titleWeight", TITLE_WEIGHT)
                .addValue("anchors", anchors.isEmpty() ? new String[] {""} : anchors.toArray(String[]::new))
                // The later parts of a named rule or section, "17 §5.1 (2)": a name holds no LIKE wildcard.
                .addValue("parts", anchors.isEmpty() ? new String[] {""}
                        : anchors.stream().map(anchor -> anchor + " (%").toArray(String[]::new))
                .addValue("limit", limit)
                .addValue("kinds", KINDS);
        return jdbc.query("""
                WITH q AS (
                    SELECT CASE WHEN :words = '' THEN NULL
                                ELSE to_tsquery('simple', assistant.immutable_unaccent(:words)) END AS terms,
                           ARRAY(SELECT to_tsquery('simple', assistant.immutable_unaccent(w.word))
                                 FROM unnest(CAST(:each AS text[])) AS w(word) WHERE w.word <> '') AS each_term,
                           ARRAY(SELECT to_tsquery('simple',
                                        assistant.immutable_unaccent(replace(p.pair, ' ', ' <-> ')))
                                 FROM unnest(CAST(:pairs AS text[])) AS p(pair) WHERE p.pair <> '') AS pair_term,
                           assistant.immutable_unaccent(:text) AS text)
                SELECT c.source, c.anchor, c.kind, c.title, c.body,
                       (SELECT count(*) FROM unnest(q.each_term) AS t(term) WHERE c.search_document @@ t.term)
                       + :pairWeight * (SELECT count(*) FROM unnest(q.pair_term) AS t(term)
                                        WHERE c.search_document @@ t.term)
                       + :titleWeight * (SELECT count(*) FROM unnest(q.each_term) AS t(term)
                                         WHERE ts_filter(c.search_document, '{a}') @@ t.term)
                       + coalesce(ts_rank_cd(c.search_document, q.terms, 32), 0)
                       + 0.5 * public.similarity(assistant.immutable_unaccent(lower(c.title)), q.text)
                       + CASE WHEN upper(c.anchor) = ANY(:anchors) OR upper(c.anchor) LIKE ANY(:parts) THEN 100
                              ELSE 0 END AS score
                FROM assistant.knowledge_chunk c, q
                WHERE c.kind = ANY(:kinds)
                  AND ((q.terms IS NOT NULL AND c.search_document @@ q.terms
                        AND (SELECT count(*) FROM unnest(q.each_term) AS t(term) WHERE c.search_document @@ t.term)
                            >= :needed)
                   OR upper(c.anchor) = ANY(:anchors)
                   OR upper(c.anchor) LIKE ANY(:parts)
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
