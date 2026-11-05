package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link KnowledgeRepository} in memory, with the port's rules for vectors (a vector counts only for its model, its
 * length and the text it was made from) and a keyword ranking the test sets per query. The SQL itself is
 * {@code KnowledgeEmbeddingIntegrationTest}'s.
 */
final class InMemoryKnowledgeRepository implements KnowledgeRepository {

    private record Key(String source, String anchor) {

        static Key of(KnowledgeChunk chunk) {
            return new Key(chunk.source(), chunk.anchor());
        }
    }

    private record Vector(String model, String hash, float[] embedding) {
    }

    private final Map<Key, KnowledgeChunk> chunks = new LinkedHashMap<>();
    private final Map<Key, Vector> vectors = new HashMap<>();
    private final Map<String, List<KnowledgeChunk>> rankings = new HashMap<>();
    /** The {@code limit} of every keyword search, in order. */
    final List<Integer> searchLimits = new ArrayList<>();

    /** The passages stored for {@code source}, in the order they were first stored. */
    List<KnowledgeChunk> chunks(String source) {
        return chunks.values().stream().filter(chunk -> chunk.source().equals(source)).toList();
    }

    /** What the keyword search returns for {@code query}, best first; nothing for a query not given. */
    void ranks(String query, KnowledgeChunk... ranking) {
        rankings.put(query, List.of(ranking));
    }

    @Override
    public Sync sync(String source, List<KnowledgeChunk> wanted, Instant builtAt) {
        int changes = 0;
        List<Key> gone = chunks.keySet().stream().filter(key -> key.source().equals(source)).toList();
        for (Key key : gone) {
            if (wanted.stream().noneMatch(chunk -> Key.of(chunk).equals(key))) {
                chunks.remove(key);
                changes++;
            }
        }
        for (KnowledgeChunk chunk : wanted) {
            KnowledgeChunk before = chunks.put(Key.of(chunk), chunk);
            if (before == null || !before.contentHash().equals(chunk.contentHash())) {
                changes++;
            }
        }
        return new Sync(changes, false);
    }

    @Override
    public List<KnowledgeChunk> search(String query, int limit, int minMatched) {
        searchLimits.add(limit);
        return rankings.getOrDefault(query, List.of()).stream().limit(limit).toList();
    }

    @Override
    public List<KnowledgeChunk> withoutEmbedding(String model, int dimensions) {
        return chunks.values().stream()
                .filter(chunk -> !current(chunk, model, dimensions))
                .sorted(Comparator.comparing(KnowledgeChunk::source).thenComparing(KnowledgeChunk::anchor))
                .toList();
    }

    @Override
    public boolean saveEmbedding(KnowledgeChunk chunk, String model, float[] embedding) {
        KnowledgeChunk stored = chunks.get(Key.of(chunk));
        if (stored == null || !stored.contentHash().equals(chunk.contentHash())) {
            return false;
        }
        vectors.put(Key.of(chunk), new Vector(model, chunk.contentHash(), embedding));
        return true;
    }

    @Override
    public List<EmbeddedChunk> embedded(String model, int dimensions) {
        return chunks.values().stream()
                .filter(chunk -> current(chunk, model, dimensions))
                .map(chunk -> new EmbeddedChunk(chunk, vectors.get(Key.of(chunk)).embedding()))
                .toList();
    }

    private boolean current(KnowledgeChunk chunk, String model, int dimensions) {
        Vector vector = vectors.get(Key.of(chunk));
        return vector != null && vector.model().equals(model) && vector.hash().equals(chunk.contentHash())
                && vector.embedding().length == dimensions;
    }
}
